package bme.mediai.voiceprofile.service;

import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.UserRepository;
import bme.mediai.voiceprofile.api.VoiceProfileCreateRequest;
import bme.mediai.voiceprofile.api.VoiceProfileResponse;
import bme.mediai.voiceprofile.exception.VoiceProfileNotFoundException;
import bme.mediai.voiceprofile.persistence.VoiceProfile;
import bme.mediai.voiceprofile.persistence.VoiceProfileRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class VoiceProfileService {

    private final VoiceProfileRepository repository;
    private final UserRepository userRepository;

    public VoiceProfileService(VoiceProfileRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Transactional
    public VoiceProfileResponse create(VoiceProfileCreateRequest request) {
        requireUserExists(request.userId());

        repository.findByUserIdAndActiveTrue(request.userId())
                .ifPresent(VoiceProfile::deactivate);
        // A flush kell: IDENTITY miatt az új sor INSERT-je azonnal lefut, a régi
        // sor UPDATE-je viszont csak commitkor — addig két aktív sor sértené az indexet.
        repository.flush();

        VoiceProfile profile = new VoiceProfile(
                request.userId(),
                request.wavPath(),
                request.embeddingModel(),
                request.threshold(),
                request.netSpeechSec(),
                request.microphone(),
                request.recordedAt());

        return VoiceProfileResponse.from(repository.save(profile));
    }

    @Transactional(readOnly = true)
    public VoiceProfileResponse findActive(Long userId) {
        requireUserExists(userId);
        return repository.findByUserIdAndActiveTrue(userId)
                .map(VoiceProfileResponse::from)
                .orElseThrow(() -> new VoiceProfileNotFoundException(userId));
    }

    @Transactional(readOnly = true)
    public List<VoiceProfileResponse> findHistory(Long userId) {
        requireUserExists(userId);
        return repository.findByUserIdOrderByRecordedAtDesc(userId)
                .stream()
                .map(VoiceProfileResponse::from)
                .toList();
    }

    private void requireUserExists(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
    }
}
