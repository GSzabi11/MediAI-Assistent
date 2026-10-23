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
import bme.mediai.statement.service.StatementService;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class MedicationService {

    private final MedicationRepository repository;
    private final PatientRepository patientRepository;
    private final StatementApprovalService approvalService;
    private final StatementService statementService;
    private final ApplicationEventPublisher events;

    public MedicationService(MedicationRepository repository, PatientRepository patientRepository,
            StatementApprovalService approvalService, StatementService statementService,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.approvalService = approvalService;
        this.statementService = statementService;
        this.events = events;
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

        MedicationResponse saved = MedicationResponse.from(repository.save(medication), true);
        events.publishEvent(new MedicationsChangedEvent(request.patientId()));
        return saved;
    }

    // Az automatikus dokumentum-feldolgozás: a forrás-állítás még PENDING, ezért a tétel
    // "nem ellenőrzött" (verified = false), amíg az orvos el nem fogadja az állítást.
    // Azonos nevű aktív gyógyszer mellett (minden ambuláns lap felsorolja a szedett gyógyszereket):
    // - adag nélküli új tétel, vagy ugyanaz az adag → nem veszi fel újra;
    // - adagos új tétel: a pipeline adag nélküli tétele leáll (az adagos váltja); a pipeline más
    //   adagú tétele leáll, ha a forrása nem újabb (clinicalTime) — különben az új (régebbi
    //   dokumentumból jött) adag kerül fel eleve leállítottként, előzménynek. Kézi tételt a
    //   pipeline sosem állít le: arról az orvos dönt.
    @Transactional
    public PipelineMedicationResult createFromPipeline(Long patientId, String name, String activeIngredient,
            BigDecimal dose, String doseUnit, String route, Long sourceStatementId, OffsetDateTime clinicalTime) {
        String stripped = name.strip();
        List<Medication> sameName = repository.findActiveByPatientAndName(patientId, stripped);
        List<String> notes = new ArrayList<>();
        if (!sameName.isEmpty() && (dose == null || sameName.stream().anyMatch(m -> sameDose(m, dose, doseUnit)))) {
            notes.add("Gyógyszer már aktívan szerepel a listán, nem vettük fel újra: " + stripped);
            return new PipelineMedicationResult(Optional.empty(), notes);
        }

        boolean newerDoseExists = false;
        for (Medication existing : sameName) {
            if (existing.getOrigin() != EntryOrigin.DOCUMENT_PIPELINE) {
                continue;
            }
            if (existing.getDose() == null) {
                existing.discontinue();
                notes.add(stripped + ": az adag nélküli tétel helyére az adagos került (leállítva: #"
                        + existing.getId() + ")");
            } else if (!isNewer(existing, clinicalTime)) {
                existing.discontinue();
                notes.add(stripped + ": a korábbi adag (" + doseText(existing) + ") leállítva, az új adag: "
                        + hu(dose) + " " + doseUnit);
            } else {
                newerDoseExists = true;
            }
        }

        Medication medication = new Medication(patientId, stripped, activeIngredient, dose, doseUnit, route,
                sourceStatementId, EntryOrigin.DOCUMENT_PIPELINE);
        if (newerDoseExists) {
            medication.discontinue();
            notes.add(stripped + ": újabb dokumentumban más adaggal szerepel — ez az adag leállítottként került fel");
        }
        MedicationResponse saved = MedicationResponse.from(repository.save(medication), false);
        events.publishEvent(new MedicationsChangedEvent(patientId));
        return new PipelineMedicationResult(Optional.of(saved), notes);
    }

    public record PipelineMedicationResult(Optional<MedicationResponse> created, List<String> notes) {
    }

    private static boolean sameDose(Medication m, BigDecimal dose, String doseUnit) {
        return m.getDose() != null && m.getDose().compareTo(dose) == 0
                && m.getDoseUnit() != null && m.getDoseUnit().strip().equalsIgnoreCase(doseUnit.strip());
    }

    private static String hu(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String doseText(Medication m) {
        return hu(m.getDose()) + " " + m.getDoseUnit();
    }

    // A meglévő tétel forrása szigorúan újabb-e; azonos időnél (pl. ugyanaz a lelet: „Gyógyszerei”,
    // majd „Javasolt terápia”) a később feldolgozott, vagyis a szövegben későbbi az érvényes
    private boolean isNewer(Medication existing, OffsetDateTime clinicalTime) {
        if (existing.getSourceStatementId() == null || clinicalTime == null) {
            return false;
        }
        OffsetDateTime existingTime = statementService.findById(existing.getSourceStatementId()).clinicalTime();
        return existingTime != null && existingTime.isAfter(clinicalTime);
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
        repository.flush();
        events.publishEvent(new MedicationsChangedEvent(medication.getPatientId()));
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
