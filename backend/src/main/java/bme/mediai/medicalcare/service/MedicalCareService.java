package bme.mediai.medicalcare.service;

import bme.mediai.medicalcare.api.MedicalCareCreateRequest;
import bme.mediai.medicalcare.api.MedicalCareResponse;
import bme.mediai.medicalcare.exception.MedicalCareClosedException;
import bme.mediai.medicalcare.exception.MedicalCareNotFoundException;
import bme.mediai.medicalcare.persistence.MedicalCare;
import bme.mediai.medicalcare.persistence.MedicalCareRepository;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.PatientRepository;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MedicalCareService {

    private final MedicalCareRepository repository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;

    public MedicalCareService(MedicalCareRepository repository, PatientRepository patientRepository,
            UserRepository userRepository) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public MedicalCareResponse create(MedicalCareCreateRequest request) {
        requirePatientExists(request.patientId());
        if (!userRepository.existsById(request.userId())) {
            throw new UserNotFoundException(request.userId());
        }

        MedicalCare medicalCare = new MedicalCare(request.patientId(), request.userId(), request.type());
        return MedicalCareResponse.from(repository.save(medicalCare));
    }

    @Transactional(readOnly = true)
    public MedicalCareResponse findById(Long id) {
        return MedicalCareResponse.from(getMedicalCare(id));
    }

    @Transactional(readOnly = true)
    public List<MedicalCareResponse> findByPatient(Long patientId) {
        requirePatientExists(patientId);
        return repository.findByPatientIdOrderByStartedAtDesc(patientId)
                .stream()
                .map(MedicalCareResponse::from)
                .toList();
    }

    @Transactional
    public MedicalCareResponse close(Long id) {
        getMedicalCare(id);
        if (repository.close(id) == 0) {
            throw new MedicalCareClosedException(id);
        }
        return MedicalCareResponse.from(getMedicalCare(id));
    }

    private MedicalCare getMedicalCare(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new MedicalCareNotFoundException(id));
    }

    private void requirePatientExists(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
    }
}
