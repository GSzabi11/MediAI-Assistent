package bme.mediai.recording.service;

import bme.mediai.medicalcare.exception.MedicalCareClosedException;
import bme.mediai.medicalcare.exception.MedicalCareNotFoundException;
import bme.mediai.medicalcare.exception.MedicalCarePatientMismatchException;
import bme.mediai.medicalcare.persistence.MedicalCare;
import bme.mediai.medicalcare.persistence.MedicalCareRepository;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.PatientRepository;
import bme.mediai.recording.api.RecordingCreateRequest;
import bme.mediai.recording.api.RecordingResponse;
import bme.mediai.recording.exception.RecordingNotFoundException;
import bme.mediai.recording.persistence.Recording;
import bme.mediai.recording.persistence.RecordingRepository;
import bme.mediai.recording.persistence.RecordingStatus;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.UserRepository;
import bme.mediai.voiceprofile.service.VoiceProfileService;

import bme.mediai.common.storage.AudioStorage;
import bme.mediai.processing.service.VoiceServiceClient;
import bme.mediai.recording.api.RecordingJobRequest;
import bme.mediai.recording.persistence.RecordingMode;
import bme.mediai.voiceprofile.persistence.VoiceProfile;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class RecordingService {

    private final RecordingRepository repository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final VoiceProfileService voiceProfileService;
    private final MedicalCareRepository medicalCareRepository;
    private final AudioStorage storage;
    private final VoiceServiceClient voiceService;
    private final ApplicationEventPublisher events;
    private final String defaultAsrModel;

    public RecordingService(RecordingRepository repository, PatientRepository patientRepository,
            UserRepository userRepository, VoiceProfileService voiceProfileService,
            MedicalCareRepository medicalCareRepository, AudioStorage storage, VoiceServiceClient voiceService,
            ApplicationEventPublisher events, @Value("${mediai.voice-service.asr-model}") String defaultAsrModel) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.voiceProfileService = voiceProfileService;
        this.medicalCareRepository = medicalCareRepository;
        this.storage = storage;
        this.voiceService = voiceService;
        this.events = events;
        this.defaultAsrModel = defaultAsrModel;
    }

    // A böngészőben készült felvétel feltöltése (docs/voice-module.md): a hang a Spring tárolójába
    // kerül, a felvétel rögtön PROCESSING, és a commit után a Python hangszolgáltatás kapja meg az
    // orvos (felvételkor aktív) hanglenyomatával. A nyers hangot a feldolgozás után töröljük.
    @Transactional
    public RecordingResponse upload(Long patientId, Long userId, Long medicalCareId, RecordingMode mode,
            Integer durationSec, MultipartFile audio) {
        if (mode == null) {
            throw new IllegalArgumentException("A mód (mode) kötelező: SBO vagy ECHO");
        }
        if (durationSec == null || durationSec < 1) {
            throw new IllegalArgumentException("A felvétel hossza (durationSec) legalább 1 mp kell legyen");
        }
        requirePatientExists(patientId);
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
        if (medicalCareId != null) {
            requireOpenMedicalCareOfPatient(medicalCareId, patientId);
        }
        VoiceProfile profile = voiceProfileService.requireUsableProfile(userId);

        String dir = "recordings/" + patientId;
        String audioPath = storage.save(audio, dir, UUID.randomUUID().toString());
        try {
            Recording recording = new Recording(patientId, userId, medicalCareId, profile.getId(), mode, audioPath,
                    defaultAsrModel);
            recording.changeStatus(RecordingStatus.PROCESSING, durationSec);
            Recording saved = repository.saveAndFlush(recording);

            List<Float> embedding = new ArrayList<>();
            for (float v : profile.getEmbedding()) {
                embedding.add(v);
            }
            events.publishEvent(new RecordingUploadedEvent(new RecordingJobRequest(saved.getId(),
                    storage.resolve(audioPath).toString(), mode.name(), embedding,
                    voiceService.callbackUrl("/api/recordings/" + saved.getId() + "/processing-result"))));
            return RecordingResponse.from(repository.findById(saved.getId()).orElseThrow());
        } catch (RuntimeException e) {
            storage.deleteQuietly(audioPath);
            throw e;
        }
    }

    @Transactional
    public RecordingResponse create(RecordingCreateRequest request) {
        requirePatientExists(request.patientId());
        if (!userRepository.existsById(request.userId())) {
            throw new UserNotFoundException(request.userId());
        }
        if (request.medicalCareId() != null) {
            requireOpenMedicalCareOfPatient(request.medicalCareId(), request.patientId());
        }

        // A kliens nem választhat profilt: mindig az orvos aktuálisan aktív profilja, így nem kerülhet
        // be más orvosé vagy egy régi. Hangprofil nélkül nincs felvétel (409): a szerepkiosztás
        // (ki az orvos?) a hanglenyomatra épül.
        Long voiceProfileId = voiceProfileService.requireUsableProfile(request.userId()).getId();

        Recording recording = new Recording(
                request.patientId(),
                request.userId(),
                request.medicalCareId(),
                voiceProfileId,
                request.mode(),
                request.audioPath(),
                request.asrModel());

        return RecordingResponse.from(repository.save(recording));
    }

    @Transactional(readOnly = true)
    public RecordingResponse findById(Long id) {
        return repository.findById(id)
                .map(RecordingResponse::from)
                .orElseThrow(() -> new RecordingNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<RecordingResponse> findByPatient(Long patientId) {
        requirePatientExists(patientId);
        return repository.findByPatientIdOrderByRecordedAtDesc(patientId)
                .stream()
                .map(RecordingResponse::from)
                .toList();
    }

    @Transactional
    public RecordingResponse changeStatus(Long id, RecordingStatus status, Integer durationSec) {
        Recording recording = repository.findById(id)
                .orElseThrow(() -> new RecordingNotFoundException(id));
        recording.changeStatus(status, durationSec);
        return RecordingResponse.from(recording);
    }

    // Identitás-kapu: a DB összetett FK-ja is kizárja az eltérést, ez csak 500 helyett 409-et ad.
    private void requireOpenMedicalCareOfPatient(Long medicalCareId, Long patientId) {
        MedicalCare medicalCare = medicalCareRepository.findById(medicalCareId)
                .orElseThrow(() -> new MedicalCareNotFoundException(medicalCareId));
        if (!medicalCare.getPatientId().equals(patientId)) {
            throw new MedicalCarePatientMismatchException(medicalCareId, medicalCare.getPatientId(), patientId);
        }
        if (medicalCare.isClosed()) {
            throw new MedicalCareClosedException(medicalCareId);
        }
    }

    private void requirePatientExists(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
    }
}
