package bme.mediai.medication.service;

import bme.mediai.medication.api.MedicationCreateRequest;
import bme.mediai.medication.api.MedicationResponse;
import bme.mediai.medication.exception.MedicationAlreadyDiscontinuedException;
import bme.mediai.medication.exception.MedicationNotFoundException;
import bme.mediai.medication.persistence.Medication;
import bme.mediai.medication.persistence.MedicationRepository;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.PatientRepository;
import bme.mediai.statement.service.StatementApprovalService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MedicationService {

    private final MedicationRepository repository;
    private final PatientRepository patientRepository;
    private final StatementApprovalService approvalService;

    public MedicationService(MedicationRepository repository, PatientRepository patientRepository,
            StatementApprovalService approvalService) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.approvalService = approvalService;
    }

    @Transactional
    public MedicationResponse create(MedicationCreateRequest request) {
        requirePatientExists(request.patientId());
        // A gyógyszerlista orvosi tényként jelenik meg: csak jóváhagyott állításból
        if (request.sourceStatementId() != null) {
            approvalService.requireApprovedStatementOfPatient(request.sourceStatementId(), request.patientId());
        }

        Medication medication = new Medication(
                request.patientId(),
                request.name(),
                request.activeIngredient(),
                request.dose(),
                request.doseUnit(),
                request.route(),
                request.sourceStatementId());

        return MedicationResponse.from(repository.save(medication));
    }

    @Transactional(readOnly = true)
    public MedicationResponse findById(Long id) {
        return MedicationResponse.from(getMedication(id));
    }

    @Transactional(readOnly = true)
    public List<MedicationResponse> findByPatient(Long patientId, boolean activeOnly) {
        requirePatientExists(patientId);
        List<Medication> medications = activeOnly
                ? repository.findByPatientIdAndActiveTrueOrderByIdDesc(patientId)
                : repository.findByPatientIdOrderByIdDesc(patientId);
        return medications.stream()
                .map(MedicationResponse::from)
                .toList();
    }

    @Transactional
    public MedicationResponse discontinue(Long id) {
        Medication medication = getMedication(id);
        if (!medication.isActive()) {
            throw new MedicationAlreadyDiscontinuedException(id);
        }
        medication.discontinue();
        return MedicationResponse.from(medication);
    }

    private Medication getMedication(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new MedicationNotFoundException(id));
    }

    private void requirePatientExists(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
    }
}
