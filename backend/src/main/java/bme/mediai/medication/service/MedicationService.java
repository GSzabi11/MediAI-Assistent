package bme.mediai.medication.service;

import bme.mediai.common.persistence.EntryOrigin;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
        // A gyógyszerlista orvosi tényként jelenik meg: kézzel csak jóváhagyott állításból
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
                request.sourceStatementId(),
                EntryOrigin.MANUAL);

        return MedicationResponse.from(repository.save(medication), true);
    }

    // Az automatikus dokumentum-feldolgozás: a forrás-állítás még PENDING, ezért a tétel
    // "nem ellenőrzött" (verified = false), amíg az orvos el nem fogadja az állítást.
    // Ha a betegnek már van ugyanilyen nevű aktív gyógyszere, nem veszi fel újra (üres Optional):
    // minden ambuláns lap felsorolja a szedett gyógyszereket, különben minden lelet duplikálna.
    @Transactional
    public Optional<MedicationResponse> createFromPipeline(Long patientId, String name, String activeIngredient,
            BigDecimal dose, String doseUnit, String route, Long sourceStatementId) {
        if (repository.existsActiveForPatient(patientId, name)) {
            return Optional.empty();
        }
        Medication medication = new Medication(patientId, name.strip(), activeIngredient, dose, doseUnit, route,
                sourceStatementId, EntryOrigin.DOCUMENT_PIPELINE);
        return Optional.of(MedicationResponse.from(repository.save(medication), false));
    }

    @Transactional(readOnly = true)
    public MedicationResponse findById(Long id) {
        return withVerification(List.of(getMedication(id))).getFirst();
    }

    @Transactional(readOnly = true)
    public List<MedicationResponse> findByPatient(Long patientId, boolean activeOnly) {
        requirePatientExists(patientId);
        List<Medication> medications = activeOnly
                ? repository.findByPatientIdAndActiveTrueOrderByIdDesc(patientId)
                : repository.findByPatientIdOrderByIdDesc(patientId);
        return withVerification(medications);
    }

    @Transactional
    public MedicationResponse discontinue(Long id) {
        Medication medication = getMedication(id);
        if (!medication.isActive()) {
            throw new MedicationAlreadyDiscontinuedException(id);
        }
        medication.discontinue();
        return withVerification(List.of(medication)).getFirst();
    }

    // Kézi tétel mindig ellenőrzött (létrehozáskor csak jóváhagyott állítás lehetett forrás);
    // a pipeline-tétel akkor, ha a forrás-állítás legfrissebb döntése ACCEPTED/MODIFIED.
    private List<MedicationResponse> withVerification(List<Medication> medications) {
        Set<Long> approved = approvalService.approvedAmong(medications.stream()
                .filter(m -> m.getOrigin() == EntryOrigin.DOCUMENT_PIPELINE)
                .map(Medication::getSourceStatementId)
                .filter(Objects::nonNull)
                .toList());
        return medications.stream()
                .map(m -> MedicationResponse.from(m,
                        m.getOrigin() == EntryOrigin.MANUAL || approved.contains(m.getSourceStatementId())))
                .toList();
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
