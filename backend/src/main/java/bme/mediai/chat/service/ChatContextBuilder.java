package bme.mediai.chat.service;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import bme.mediai.allergy.api.AllergyResponse;
import bme.mediai.allergy.service.AllergyService;
import bme.mediai.chat.api.ChatFact;
import bme.mediai.chat.persistence.ChatContextItem;
import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentRepository;
import bme.mediai.lab.persistence.LabResult;
import bme.mediai.lab.persistence.LabResultRepository;
import bme.mediai.lab.service.LabTestNames;
import bme.mediai.medication.api.MedicationResponse;
import bme.mediai.medication.service.MedicationService;
import bme.mediai.patient.persistence.Patient;
import bme.mediai.statement.api.ReviewStatus;
import bme.mediai.statement.api.StatementResponse;
import bme.mediai.statement.persistence.StatementSource;
import bme.mediai.statement.persistence.StatementType;
import bme.mediai.statement.service.StatementService;
import bme.mediai.summary.api.SummaryFacts;
import bme.mediai.summary.service.SummaryFactsBuilder;

// A chat-modell strukturált kontextusa a beteg TELJES előzményéből (nem csak 6 hónap): laborok
// vizsgálatonként, a leletekből kinyert állítások, gyógyszerek, allergiák. A dokumentumok szövegéből
// a kérdéshez illő darabokat a Python kéri le külön (ChatService.retrieve). Név, TAJ, lakcím nem megy.
@Component
public class ChatContextBuilder {

    private static final ZoneId CLINICAL_ZONE = ZoneId.of("Europe/Budapest");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd.");
    // vizsgálatonként a legutóbbi mellett ennyi korábbi érték (trendhez)
    private static final int PREVIOUS_LAB_VALUES = 3;

    public static final String BACKGROUND = "BACKGROUND";
    public static final String LAB_RESULT = "LAB_RESULT";
    public static final String STATEMENT = "STATEMENT";
    public static final String MEDICATION = "MEDICATION";
    public static final String ALLERGY = "ALLERGY";
    public static final String CHUNK = "CHUNK";

    // facts: a modellnek (hivatkozható + háttér); citable: ami a chat_message.context-be kerül
    public record Built(List<ChatFact> facts, List<ChatContextItem> citable) {
    }

    private final LabResultRepository labResultRepository;
    private final StatementService statementService;
    private final MedicationService medicationService;
    private final AllergyService allergyService;
    private final DocumentRepository documentRepository;

    public ChatContextBuilder(LabResultRepository labResultRepository, StatementService statementService,
            MedicationService medicationService, AllergyService allergyService,
            DocumentRepository documentRepository) {
        this.labResultRepository = labResultRepository;
        this.statementService = statementService;
        this.medicationService = medicationService;
        this.allergyService = allergyService;
        this.documentRepository = documentRepository;
    }

    public Built build(Patient patient) {
        Long patientId = patient.getId();
        List<Document> documents = documentRepository.findByPatientIdOrderByUploadedAtDesc(patientId).stream()
                .filter(d -> d.getType() != null)
                .toList();
        Map<Long, Document> docsById = documents.stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        List<ChatFact> facts = new ArrayList<>();

        // --- háttér (nem hivatkozható) ---
        if (patient.getBirthDate() != null) {
            int age = Period.between(patient.getBirthDate(), LocalDate.now(CLINICAL_ZONE)).getYears();
            facts.add(new ChatFact(BACKGROUND, null, null, "Életkor: " + age + " év", null));
        }
        if (!documents.isEmpty()) {
            facts.add(new ChatFact(BACKGROUND, null, null, "Dokumentumok: " + documents.stream()
                    .sorted(Comparator.comparing(ChatContextBuilder::docDate).reversed())
                    .map(d -> documentTitle(d) + " " + DATE.format(docDate(d)))
                    .collect(Collectors.joining(", ")), null));
        }

        // --- labor: vizsgálatonként (egységes név + mértékegység) a legutóbbi, mellette néhány korábbi ---
        Map<String, List<LabResult>> byTest = new LinkedHashMap<>();
        for (LabResult r : labResultRepository.findByPatientIdOrderByMeasuredAtDescIdDesc(patientId)) {
            String key = LabTestNames.canonical(r.getName()) + "|" + LabTestNames.canonicalUnit(r.getUnit());
            byTest.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        byTest.values().stream()
                .map(series -> new LabEntry(SummaryFactsBuilder.labFact(series.getFirst()), series))
                // a tartományon kívüliek elöl (a hosszú listában így nem sikkadnak el)
                .sorted(Comparator.comparing((LabEntry e) -> e.latest().status() == null)
                        .thenComparing(e -> e.latest().name()))
                .map(ChatContextBuilder::labFact)
                .forEach(facts::add);

        // --- a leletekből kinyert állítások (a szabály-állítások a labor minősítésében már benne vannak) ---
        Map<Long, StatementResponse> statements = new LinkedHashMap<>();
        for (StatementResponse s : statementService.findByPatient(patientId)) {
            if (s.type() == StatementType.DETERMINISTIC_RULE || s.reviewStatus() == ReviewStatus.REJECTED) {
                continue;
            }
            statements.put(s.id(), s);
            String reviewed = s.reviewStatus() == ReviewStatus.PENDING ? "" : " [orvos által jóváhagyva]";
            String date = DATE.format(s.clinicalTime().atZoneSameInstant(CLINICAL_ZONE).toLocalDate());
            facts.add(new ChatFact(STATEMENT, s.id(), sourceLabel(s.source(), docsById),
                    (s.label() == null ? "" : s.label() + ": ") + s.effectiveText() + " (" + date + ")" + reviewed,
                    s.source()));
        }

        // --- gyógyszer, allergia: a forrás-állításuk forrásával hivatkozható; kézi rögzítés csak háttér ---
        for (MedicationResponse m : medicationService.findByPatient(patientId, true)) {
            String text = "Aktív gyógyszer: " + m.name()
                    + (m.activeIngredient() == null ? "" : " (" + m.activeIngredient() + ")")
                    + (m.dose() == null ? "" : ", " + m.dose().stripTrailingZeros().toPlainString().replace('.', ',')
                            + (m.doseUnit() == null ? "" : " " + m.doseUnit()))
                    + (m.route() == null ? "" : ", " + m.route())
                    + (m.verified() ? "" : " [nem ellenőrzött]");
            facts.add(withSource(MEDICATION, m.id(), text, m.sourceStatementId(), statements, docsById));
        }
        for (AllergyResponse a : allergyService.findByPatient(patientId)) {
            String text = "Allergia: " + a.substance() + (a.verified() ? "" : " [nem ellenőrzött]");
            facts.add(withSource(ALLERGY, a.id(), text, a.sourceStatementId(), statements, docsById));
        }

        List<ChatContextItem> citable = facts.stream()
                .filter(f -> f.source() != null)
                .map(f -> new ChatContextItem(f.kind(), f.id(), f.label(), f.source()))
                .toList();
        return new Built(facts, citable);
    }

    // A visszakeresett darabok felirata (a dokumentum címe és dátuma, mint a felület forrás-chipjein)
    public static String documentLabel(Document document) {
        return documentTitle(document) + " · " + DATE.format(docDate(document));
    }

    private record LabEntry(SummaryFacts.LabFact latest, List<LabResult> series) {
    }

    private static ChatFact labFact(LabEntry entry) {
        SummaryFacts.LabFact f = entry.latest();
        StringBuilder text = new StringBuilder(f.name()).append(": ").append(f.value());
        if (f.unit() != null) {
            text.append(' ').append(f.unit());
        }
        if (f.reference() != null) {
            text.append(" (referencia: ").append(f.reference()).append(')');
        }
        if (f.status() != null) {
            text.append(" — a referencia ").append(f.status().equals("eltér") ? "értékétől ELTÉR"
                    : f.status().equals("felett") ? "FELETT" : "ALATT");
        }
        text.append(", ").append(DATE.format(f.date()));
        List<String> previous = entry.series().stream().skip(1).limit(PREVIOUS_LAB_VALUES)
                .map(SummaryFactsBuilder::labFact)
                .map(p -> p.value() + " (" + DATE.format(p.date()) + ")")
                .toList();
        if (!previous.isEmpty()) {
            text.append("; korábban: ").append(String.join(", ", previous));
        }
        return new ChatFact(LAB_RESULT, f.id(), f.name() + " · " + DATE.format(f.date()), text.toString(),
                new StatementSource.Lab(f.id()), f.status() != null);
    }

    private static ChatFact withSource(String kind, Long id, String text, Long sourceStatementId,
            Map<Long, StatementResponse> statements, Map<Long, Document> docsById) {
        StatementResponse s = sourceStatementId == null ? null : statements.get(sourceStatementId);
        if (s == null) {
            return new ChatFact(BACKGROUND, null, null, text, null);
        }
        return new ChatFact(kind, id, sourceLabel(s.source(), docsById), text, s.source());
    }

    private static String sourceLabel(StatementSource source, Map<Long, Document> docsById) {
        return switch (source) {
            case StatementSource.Document d -> labelOf(d.documentId(), docsById);
            case StatementSource.Image i -> labelOf(i.documentId(), docsById);
            case StatementSource.Audio a -> "Felvétel #" + a.recordingId();
            case StatementSource.Lab l -> "Labormérés #" + l.labResultId();
        };
    }

    private static String labelOf(Long documentId, Map<Long, Document> docsById) {
        Document d = docsById.get(documentId);
        return d == null ? "Dokumentum #" + documentId : documentLabel(d);
    }

    private static String documentTitle(Document d) {
        return switch (d.getType()) {
            case LAB -> "Laborlelet";
            case AMBULANCE -> "Ambuláns lap";
            case IMAGING -> "Képalkotó lelet";
        };
    }

    private static LocalDate docDate(Document d) {
        return d.getClinicalDate() != null ? d.getClinicalDate()
                : d.getUploadedAt().atZoneSameInstant(CLINICAL_ZONE).toLocalDate();
    }
}
