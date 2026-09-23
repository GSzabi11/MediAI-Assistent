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
import bme.mediai.voiceprofile.persistence.VoiceProfile;
import bme.mediai.voiceprofile.persistence.VoiceProfileRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RecordingService {

    private final RecordingRepository repository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final VoiceProfileRepository voiceProfileRepository;
    private final MedicalCareRepository medicalCareRepository;

    public RecordingService(RecordingRepository repository, PatientRepository patientRepository,
            UserRepository userRepository, VoiceProfileRepository voiceProfileRepository,
            MedicalCareRepository medicalCareRepository) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.voiceProfileRepository = voiceProfileRepository;
        this.medicalCareRepository = medicalCareRepository;
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

        // A kliens nem választhat profilt: mindig az orvos aktuálisan aktív profilja,
        // így nem kerülhet be más orvosé vagy egy régi (.bak) profil.
        Long voiceProfileId = voiceProfileRepository.findByUserIdAndActiveTrue(request.userId())
                .map(VoiceProfile::getId)
                .orElse(null);

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
