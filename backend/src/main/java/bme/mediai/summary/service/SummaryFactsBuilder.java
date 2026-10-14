package bme.mediai.summary.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import bme.mediai.allergy.service.AllergyService;
import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentRepository;
import bme.mediai.lab.persistence.LabResult;
import bme.mediai.lab.persistence.LabResultRepository;
import bme.mediai.lab.service.LabTestNames;
import bme.mediai.medication.service.MedicationService;
import bme.mediai.patient.persistence.Patient;
import bme.mediai.recording.persistence.Recording;
import bme.mediai.recording.persistence.RecordingRepository;
import bme.mediai.recording.persistence.RecordingStatus;
import bme.mediai.recordingsummary.persistence.RecordingSummary;
import bme.mediai.recordingsummary.persistence.RecordingSummaryRepository;
import bme.mediai.recordingsummary.persistence.RecordingSummaryStatus;
import bme.mediai.rule.service.LabReferenceRangeRule;
import bme.mediai.statement.api.ReviewStatus;
import bme.mediai.statement.api.StatementResponse;
import bme.mediai.statement.persistence.StatementSource;
import bme.mediai.statement.persistence.StatementType;
import bme.mediai.statement.service.StatementService;
import bme.mediai.summary.api.SummaryFacts;

// A modell bemenetének összeállítása: a beteg adatai a megadott időszakban. Csak strukturált,
// már ellenőrzött adat megy (laborsorok, állítások, gyógyszer, allergia) — a leletek nyers
// szövege nem (hosszú, és a belőle kinyert állítások már itt vannak). A hangfelvételekből a
// felvétel összefoglalója megy (a felhasználó döntése), nem az átirat: a teljes átirat a 4B-s
// modell kontextusát szétfeszítené, az összefoglaló pedig már forráshoz kötött.
@Component
public class SummaryFactsBuilder {

    private static final ZoneId CLINICAL_ZONE = ZoneId.of("Europe/Budapest");

    public record Built(SummaryFacts facts, Map<String, List<Long>> inputIds) {
    }

    private final LabResultRepository labResultRepository;
    private final StatementService statementService;
    private final MedicationService medicationService;
    private final AllergyService allergyService;
    private final DocumentRepository documentRepository;
    private final RecordingRepository recordingRepository;
    private final RecordingSummaryRepository recordingSummaryRepository;

    public SummaryFactsBuilder(LabResultRepository labResultRepository, StatementService statementService,
            MedicationService medicationService, AllergyService allergyService,
            DocumentRepository documentRepository, RecordingRepository recordingRepository,
            RecordingSummaryRepository recordingSummaryRepository) {
        this.recordingRepository = recordingRepository;
        this.recordingSummaryRepository = recordingSummaryRepository;
        this.labResultRepository = labResultRepository;
        this.statementService = statementService;
        this.medicationService = medicationService;
        this.allergyService = allergyService;
        this.documentRepository = documentRepository;
    }

    public Built build(Patient patient, LocalDate start, LocalDate end) {
        Long patientId = patient.getId();

        List<LabResult> labs = labResultRepository.findByPatientIdOrderByMeasuredAtDescIdDesc(patientId).stream()
                .filter(r -> inPeriod(r.getMeasuredAt().atZoneSameInstant(CLINICAL_ZONE).toLocalDate(), start, end))
                .toList();
        List<SummaryFacts.LabFact> labFacts = labs.stream().map(SummaryFactsBuilder::labFact).toList();

        List<StatementResponse> statements = statementService.findByPatient(patientId).stream()
                .filter(s -> inPeriod(s.clinicalTime().atZoneSameInstant(CLINICAL_ZONE).toLocalDate(), start, end))
                .filter(s -> s.reviewStatus() != ReviewStatus.REJECTED)
                .toList();
        List<SummaryFacts.FindingFact> findings = statements.stream()
                .filter(s -> s.type() == StatementType.DETERMINISTIC_RULE && s.source() instanceof StatementSource.Lab)
                .map(s -> new SummaryFacts.FindingFact(s.id(), s.effectiveText(), date(s)))
                .toList();
        List<SummaryFacts.StatementFact> statementFacts = statements.stream()
                .filter(s -> s.type() != StatementType.DETERMINISTIC_RULE)
                .map(s -> new SummaryFacts.StatementFact(s.id(), s.label(), s.effectiveText(), date(s),
                        s.reviewStatus().name()))
                .toList();

        List<SummaryFacts.MedicationFact> medications = medicationService.findByPatient(patientId, true).stream()
                .map(m -> new SummaryFacts.MedicationFact(m.id(), m.name(), m.activeIngredient(),
                        m.dose() == null ? null : number(m.dose()) + (m.doseUnit() == null ? "" : " " + m.doseUnit()),
                        m.route(), m.verified()))
                .toList();
        List<SummaryFacts.AllergyFact> allergies = allergyService.findByPatient(patientId).stream()
                .map(a -> new SummaryFacts.AllergyFact(a.id(), a.substance(), a.verified()))
                .toList();

        List<Document> documents = documentRepository.findByPatientIdOrderByUploadedAtDesc(patientId).stream()
                .filter(d -> d.getType() != null)
                .filter(d -> inPeriod(d.getClinicalDate() != null ? d.getClinicalDate()
                        : d.getUploadedAt().atZoneSameInstant(CLINICAL_ZONE).toLocalDate(), start, end))
                .toList();
        List<SummaryFacts.DocumentFact> documentFacts = documents.stream()
                .map(d -> new SummaryFacts.DocumentFact(d.getId(), d.getType().name(), d.getClinicalDate()))
                .toList();

        List<SummaryFacts.RecordingFact> recordings = recordingRepository.findByPatientIdOrderByRecordedAtDesc(patientId)
                .stream()
                .filter(r -> r.getStatus() == RecordingStatus.DONE)
                .filter(r -> inPeriod(r.getRecordedAt().atZoneSameInstant(CLINICAL_ZONE).toLocalDate(), start, end))
                .map(this::recordingFact)
                .flatMap(Optional::stream)
                .toList();

        Integer age = patient.getBirthDate() == null ? null : Period.between(patient.getBirthDate(), end).getYears();
        SummaryFacts facts = new SummaryFacts(start, end, age, labFacts, findings, statementFacts, medications,
                allergies, documentFacts, recordings);

        Map<String, List<Long>> ids = new LinkedHashMap<>();
        ids.put("labResultIds", labs.stream().map(LabResult::getId).toList());
        ids.put("statementIds", statements.stream().map(StatementResponse::id).toList());
        ids.put("medicationIds", medications.stream().map(SummaryFacts.MedicationFact::id).toList());
        ids.put("allergyIds", allergies.stream().map(SummaryFacts.AllergyFact::id).toList());
        ids.put("documentIds", documents.stream().map(Document::getId).toList());
        ids.put("recordingSummaryIds", recordings.stream().map(SummaryFacts.RecordingFact::summaryId).toList());
        return new Built(facts, ids);
    }

    // egy laborsor a modellnek: érték a jellel, referencia, a szabálymotor minősítése (a chat is ezt használja)
    public static SummaryFacts.LabFact labFact(LabResult r) {
        String value = r.getValueText() != null ? r.getValueText()
                : (r.getComparator() == null ? "" : r.getComparator()) + number(r.getValueNum());
        String reference = r.getRefText() != null ? r.getRefText()
                : r.getRefLow() != null && r.getRefHigh() != null ? number(r.getRefLow()) + "–" + number(r.getRefHigh())
                : r.getRefLow() != null ? "> " + number(r.getRefLow())
                : r.getRefHigh() != null ? "< " + number(r.getRefHigh()) : null;
        String status = switch (LabReferenceRangeRule.evaluate(r)) {
            case ABOVE -> "felett";
            case BELOW -> "alatt";
            case DIFFERS -> "eltér";
            default -> null;
        };
        return new SummaryFacts.LabFact(r.getId(), LabTestNames.canonical(r.getName()), value,
                LabTestNames.canonicalUnit(r.getUnit()), reference,
                r.getMeasuredAt().atZoneSameInstant(CLINICAL_ZONE).toLocalDate(), status);
    }

    // a felvétel legutóbbi elkészült összefoglalója; ha nincs, a felvétel kimarad
    private Optional<SummaryFacts.RecordingFact> recordingFact(Recording r) {
        return recordingSummaryRepository.findByRecordingIdOrderByRequestedAtDescIdDesc(r.getId()).stream()
                .filter(s -> s.getStatus() == RecordingSummaryStatus.DONE)
                .findFirst()
                .map(s -> {
                    boolean edited = s.getEditedText() != null && !s.getEditedText().isBlank();
                    return new SummaryFacts.RecordingFact(r.getId(), s.getId(), r.getMode().name(),
                            r.getRecordedAt().atZoneSameInstant(CLINICAL_ZONE).toLocalDate(),
                            edited ? s.getEditedText() : s.getSummary(), edited);
                });
    }

    private static LocalDate date(StatementResponse s) {
        return s.clinicalTime().atZoneSameInstant(CLINICAL_ZONE).toLocalDate();
    }

    private static boolean inPeriod(LocalDate d, LocalDate start, LocalDate end) {
        return d != null && !d.isBefore(start) && !d.isAfter(end);
    }

    // magyar tizedesvessző, a NUMERIC záró nullái nélkül
    private static String number(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
