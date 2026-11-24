package bme.mediai.voiceprofile.service;

import bme.mediai.common.storage.AudioStorage;
import bme.mediai.processing.service.VoiceServiceClient;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.Role;
import bme.mediai.user.persistence.User;
import bme.mediai.user.persistence.UserRepository;
import bme.mediai.voiceprofile.api.VoiceEnrollmentJobRequest;
import bme.mediai.voiceprofile.api.VoiceEnrollmentResultRequest;
import bme.mediai.voiceprofile.api.VoiceProfileResponse;
import bme.mediai.voiceprofile.exception.VoiceProfileConflictException;
import bme.mediai.voiceprofile.exception.VoiceProfileNotFoundException;
import bme.mediai.voiceprofile.exception.VoiceProfileRequiredException;
import bme.mediai.voiceprofile.persistence.VoiceProfile;
import bme.mediai.voiceprofile.persistence.VoiceProfileRepository;
import bme.mediai.voiceprofile.persistence.VoiceProfileStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Hangprofil-felvétel a böngészőből (docs/voice-module.md): a felolvasott mondatokat a Spring
// menti, a hanglenyomatot a Python hangszolgáltatás számolja, és callbackkel adja vissza.
@Service
public class VoiceProfileService {

    // A profil 12 rövid mondatot olvastat fel (profil_core.ENROLL_SENTENCES); néhány kieshet a minőségellenőrzésen
    public static final int MIN_SENTENCES = 4;
    public static final int MAX_SENTENCES = 12;
    private static final int MAX_ERROR_LENGTH = 2000;
    private static final String PROFILE_WAV = "profile.wav";

    private final VoiceProfileRepository repository;
    private final UserRepository userRepository;
    private final AudioStorage storage;
    private final VoiceServiceClient voiceService;
    private final ApplicationEventPublisher events;
    private final Validator validator;

    public VoiceProfileService(VoiceProfileRepository repository, UserRepository userRepository,
            AudioStorage storage, VoiceServiceClient voiceService, ApplicationEventPublisher events,
            Validator validator) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.storage = storage;
        this.voiceService = voiceService;
        this.events = events;
        this.validator = validator;
    }

    @Transactional
    public VoiceProfileResponse enroll(Long userId, List<MultipartFile> sentences, MultipartFile noise,
            String microphone) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getRole() != Role.DOCTOR) {
            throw new VoiceProfileConflictException("Hangprofilt csak orvos (DOCTOR) vehet fel");
        }
        if (sentences == null || sentences.size() < MIN_SENTENCES || sentences.size() > MAX_SENTENCES) {
            throw new IllegalArgumentException(
                    "A hangprofilhoz " + MIN_SENTENCES + "–" + MAX_SENTENCES + " felolvasott mondat kell");
        }
        if (repository.existsByUserIdAndStatus(userId, VoiceProfileStatus.PROCESSING)) {
            throw new VoiceProfileConflictException("Már készül egy hangprofil ehhez a felhasználóhoz");
        }

        String dir = "profiles/" + userId + "/" + UUID.randomUUID();
        try {
            List<String> sentencePaths = new ArrayList<>();
            for (int i = 0; i < sentences.size(); i++) {
                sentencePaths.add(storage.save(sentences.get(i), dir, "sentence-%02d".formatted(i + 1)));
            }
            String noisePath = noise == null || noise.isEmpty() ? null : storage.save(noise, dir, "noise");
            String mic = microphone == null || microphone.isBlank() ? null : microphone.strip();
            if (mic != null && mic.length() > 255) {
                mic = mic.substring(0, 255);
            }

            VoiceProfile saved = repository.saveAndFlush(
                    new VoiceProfile(userId, dir + "/" + PROFILE_WAV, mic, OffsetDateTime.now()));
            events.publishEvent(new VoiceEnrollmentRequestedEvent(new VoiceEnrollmentJobRequest(
                    saved.getId(),
                    sentencePaths.stream().map(p -> storage.resolve(p).toString()).toList(),
                    noisePath == null ? null : storage.resolve(noisePath).toString(),
                    storage.resolve(saved.getWavPath()).toString(),
                    voiceService.callbackUrl("/api/voice-profiles/" + saved.getId() + "/enrollment-result"))));
            return VoiceProfileResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            storage.deleteDirectoryQuietly(dir);
            // párhuzamos kérés: a részleges egyedi index (egy PROCESSING / felhasználó) fogta meg
            throw new VoiceProfileConflictException("Már készül egy hangprofil ehhez a felhasználóhoz");
        } catch (RuntimeException e) {
            storage.deleteDirectoryQuietly(dir);
            throw e;
        }
    }

    // Python callback. Az érvénytelen eredmény is FAILED-del zárja (a controller hívja a fail-t)
    @Transactional
    public VoiceProfileResponse completeEnrollment(Long id, VoiceEnrollmentResultRequest result) {
        VoiceProfile profile = repository.findById(id).orElseThrow(() -> VoiceProfileNotFoundException.byId(id));
        if (profile.getStatus() != VoiceProfileStatus.PROCESSING) {
            throw new VoiceProfileConflictException("A(z) " + id + " hangprofil eredménye már megérkezett");
        }
        Set<ConstraintViolation<VoiceEnrollmentResultRequest>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                    .collect(Collectors.joining("; ")));
        }
        String dir = profileDir(profile);
        if ("FAILED".equals(result.status())) {
            profile.fail(truncate("Hangprofil: " + (result.error() == null ? "ismeretlen hiba" : result.error())));
            storage.deleteDirectoryQuietly(dir);
            return VoiceProfileResponse.from(profile);
        }
        float[] embedding = toEmbedding(result.embedding());
        if (result.embeddingModel() == null || result.embeddingModel().isBlank()
                || result.netSpeechSec() == null || result.sentencesUsed() == null) {
            throw new IllegalArgumentException(
                    "READY eredményhez kötelező az embeddingModel, a netSpeechSec és a sentencesUsed");
        }

        repository.findByUserIdAndActiveTrue(profile.getUserId()).ifPresent(VoiceProfile::deactivate);
        // A flush kell: a régi aktív sor UPDATE-je fusson le az új aktiválása előtt (részleges egyedi index)
        repository.flush();
        profile.complete(embedding, result.embeddingModel().strip(), result.netSpeechSec(), result.sentencesUsed(),
                result.warnings() == null ? List.of() : result.warnings());
        repository.flush();
        // A felolvasott mondatok nem kellenek tovább: a kiszűrt beszéd a profile.wav-ban van
        storage.deleteDirectoryContentsExcept(dir, PROFILE_WAV);
        return VoiceProfileResponse.from(profile);
    }

    @Transactional
    public void failEnrollment(Long id, String error) {
        repository.findById(id).ifPresent(profile -> {
            if (profile.getStatus() == VoiceProfileStatus.PROCESSING) {
                profile.fail(truncate(error == null || error.isBlank() ? "Ismeretlen hiba" : error.strip()));
                storage.deleteDirectoryQuietly(profileDir(profile));
            }
        });
    }

    @Transactional(readOnly = true)
    public VoiceProfileResponse findActive(Long userId) {
        requireUserExists(userId);
        return repository.findByUserIdAndActiveTrue(userId)
                .map(VoiceProfileResponse::from)
                .orElseThrow(() -> new VoiceProfileNotFoundException(userId));
    }

    @Transactional(readOnly = true)
    public VoiceProfileResponse findById(Long id) {
        return repository.findById(id).map(VoiceProfileResponse::from)
                .orElseThrow(() -> VoiceProfileNotFoundException.byId(id));
    }

    @Transactional(readOnly = true)
    public List<VoiceProfileResponse> findHistory(Long userId) {
        requireUserExists(userId);
        return repository.findByUserIdOrderByRecordedAtDesc(userId)
                .stream()
                .map(VoiceProfileResponse::from)
                .toList();
    }

    /** Felvétel előfeltétele: aktív, kész profil hanglenyomattal (409, ha nincs). */
    @Transactional(readOnly = true)
    public VoiceProfile requireUsableProfile(Long userId) {
        return repository.findByUserIdAndActiveTrue(userId)
                .filter(VoiceProfile::isUsableForRecording)
                .orElseThrow(() -> new VoiceProfileRequiredException(userId));
    }

    private static float[] toEmbedding(List<Float> values) {
        if (values == null || values.size() != VoiceProfile.EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException(
                    "A hanglenyomat " + VoiceProfile.EMBEDDING_DIMENSION + " dimenziós kell legyen");
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            Float v = values.get(i);
            if (v == null || !Float.isFinite(v)) {
                throw new IllegalArgumentException("A hanglenyomat nem véges számot tartalmaz");
            }
            out[i] = v;
        }
        return out;
    }

    private static String profileDir(VoiceProfile profile) {
        String wav = profile.getWavPath();
        int slash = wav.lastIndexOf('/');
        return slash < 0 ? wav : wav.substring(0, slash);
    }

    private static String truncate(String message) {
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }

    private void requireUserExists(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
    }
}
