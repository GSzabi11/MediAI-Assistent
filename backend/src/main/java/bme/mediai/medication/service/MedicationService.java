package bme.mediai.medication.service;

import bme.mediai.medication.api.MedicationCreateRequest;
import bme.mediai.medication.api.MedicationResponse;
import bme.mediai.medication.exception.MedicationAlreadyDiscontinuedException;
import bme.mediai.medication.exception.MedicationNotFoundException;
import bme.mediai.medication.exception.SourceStatementNotUsableException;
import bme.mediai.medication.persistence.Medication;
import bme.mediai.medication.persistence.MedicationRepository;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.PatientRepository;
import bme.mediai.statement.exception.StatementNotFoundException;
import bme.mediai.statement.persistence.ApprovalStatus;
import bme.mediai.statement.persistence.Statement;
import bme.mediai.statement.persistence.StatementApproval;
import bme.mediai.statement.persistence.StatementApprovalRepository;
import bme.mediai.statement.persistence.StatementRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MedicationService {

    private final MedicationRepository repository;
    private final PatientRepository patientRepository;
    private final StatementRepository statementRepository;
    private final StatementApprovalRepository approvalRepository;

    public MedicationService(MedicationRepository repository, PatientRepository patientRepository,
            StatementRepository statementRepository, StatementApprovalRepository approvalRepository) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.statementRepository = statementRepository;
        this.approvalRepository = approvalRepository;
    }

    @Transactional
    public MedicationResponse create(MedicationCreateRequest request) {
        requirePatientExists(request.patientId());
        if (request.sourceStatementId() != null) {
            requireUsableSourceStatement(request.sourceStatementId(), request.patientId());
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

    // Human-in-the-loop: a gyógyszerlista orvosi tényként jelenik meg, ezért csak olyan
    // állításból származhat, amit orvos elfogadott (ACCEPTED) vagy javított (MODIFIED).
    // A beteg-egyezést a DB összetett FK-ja is kikényszeríti; ez csak érthető 409-et ad.
    private void requireUsableSourceStatement(Long statementId, Long patientId) {
        Statement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new StatementNotFoundException(statementId));
        if (!statement.getPatientId().equals(patientId)) {
            throw new SourceStatementNotUsableException("A(z) " + statementId + " állítás a(z) "
                    + statement.getPatientId() + " beteghez tartozik, nem a(z) " + patientId + " beteghez");
        }
        ApprovalStatus status = approvalRepository.findFirstByStatementIdOrderByDecidedAtDescIdDesc(statementId)
                .map(StatementApproval::getStatus)
                .orElse(null);
        if (status != ApprovalStatus.ACCEPTED && status != ApprovalStatus.MODIFIED) {
            throw new SourceStatementNotUsableException("A(z) " + statementId
                    + " állítás nincs orvosilag jóváhagyva (állapot: " + (status == null ? "PENDING" : status)
                    + "); gyógyszer csak elfogadott vagy módosított állításból rögzíthető");
        }
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
