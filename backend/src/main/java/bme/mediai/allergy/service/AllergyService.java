package bme.mediai.allergy.service;

import bme.mediai.allergy.api.AllergyCreateRequest;
import bme.mediai.allergy.api.AllergyResponse;
import bme.mediai.allergy.exception.AllergyNotFoundException;
import bme.mediai.allergy.exception.DuplicateAllergyException;
import bme.mediai.allergy.persistence.Allergy;
import bme.mediai.allergy.persistence.AllergyRepository;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.PatientRepository;
import bme.mediai.statement.service.StatementApprovalService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AllergyService {

    private final AllergyRepository repository;
    private final PatientRepository patientRepository;
    private final StatementApprovalService approvalService;

    public AllergyService(AllergyRepository repository, PatientRepository patientRepository,
            StatementApprovalService approvalService) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.approvalService = approvalService;
    }

    @Transactional
    public AllergyResponse create(AllergyCreateRequest request) {
        requirePatientExists(request.patientId());
        // Az allergialista orvosi tényként jelenik meg: csak jóváhagyott állításból
        if (request.sourceStatementId() != null) {
            approvalService.requireApprovedStatementOfPatient(request.sourceStatementId(), request.patientId());
        }
        if (repository.existsForPatient(request.patientId(), request.substance())) {
            throw new DuplicateAllergyException(request.patientId(), request.substance().strip());
        }

        Allergy allergy = new Allergy(request.patientId(), request.substance(), request.sourceStatementId());
        return AllergyResponse.from(repository.save(allergy));
    }

    @Transactional(readOnly = true)
    public AllergyResponse findById(Long id) {
        return repository.findById(id)
                .map(AllergyResponse::from)
                .orElseThrow(() -> new AllergyNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<AllergyResponse> findByPatient(Long patientId) {
        requirePatientExists(patientId);
        return repository.findByPatientIdOrderBySubstance(patientId)
                .stream()
                .map(AllergyResponse::from)
                .toList();
    }

    private void requirePatientExists(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
    }
}
