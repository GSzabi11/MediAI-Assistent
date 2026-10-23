package bme.mediai.medicalhistory.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import bme.mediai.allergy.service.AllergyService;
import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentRepository;
import bme.mediai.document.persistence.DocumentStatus;
import bme.mediai.document.persistence.DocumentType;
import bme.mediai.lab.persistence.LabResult;
import bme.mediai.lab.persistence.LabResultRepository;
import bme.mediai.medicalhistory.api.MedicalHistoryFacts;
import bme.mediai.medicalhistory.persistence.DocumentDigest;
import bme.mediai.medicalhistory.persistence.DocumentDigestRepository;
import bme.mediai.medicalhistory.persistence.MedicalHistory;
import bme.mediai.medication.service.MedicationService;
import bme.mediai.patient.persistence.Patient;
import bme.mediai.summary.api.SummaryFacts;
import bme.mediai.summary.service.SummaryFactsBuilder;

// A kórtörténet bemenete: a beteg TELJES előzménye. A nem labor-dokumentumok szövege megy (a Python
// dokumentumonként egyszer kivonatot készít belőle, és a kórtörténet a kivonatokból épül — így a
// modell kontextusa nem telik meg); a laborleleteknél csak a szabálymotor szerinti eltérések.
@Component
public class MedicalHistoryFactsBuilder {

    private static final ZoneId CLINICAL_ZONE = ZoneId.of("Europe/Budapest");
    // a lelet szövegéből legfeljebb ennyi megy a kivonathoz
    private static final int MAX_DOCUMENT_CHARS = 12000;
    // az összefoglaló időszaka — ugyanaz, mint a Klinikai státuszé (a felhasználó döntése, 2026-10-23)
    private static final int SUMMARY_MONTHS = 6;

    public record Built(MedicalHistoryFacts facts, List<MedicalHistory.Medication> medications,
            Map<String, List<Long>> inputIds) {
    }

    private final DocumentRepository documentRepository;
    private final DocumentDigestRepository digestRepository;
    private final LabResultRepository labResultRepository;
    private final MedicationService medicationService;
    private final AllergyService allergyService;
    private final SummaryFactsBuilder summaryFactsBuilder;

    public MedicalHistoryFactsBuilder(DocumentRepository documentRepository, DocumentDigestRepository digestRepository,
            LabResultRepository labResultRepository, MedicationService medicationService,
            AllergyService allergyService, SummaryFactsBuilder summaryFactsBuilder) {
        this.summaryFactsBuilder = summaryFactsBuilder;
        this.documentRepository = documentRepository;
        this.digestRepository = digestRepository;
        this.labResultRepository = labResultRepository;
        this.medicationService = medicationService;
        this.allergyService = allergyService;
    }

    public Built build(Patient patient) {
        return build(patient, d -> true);
    }

    // Feldolgozott, típussal és dátummal rendelkező dokumentum — csak ilyen kerülhet idővonalra / epikrízisbe
    public static boolean usable(Document d) {
        return d.getStatus() == DocumentStatus.PROCESSED && d.getType() != null && d.getClinicalDate() != null;
    }

    // include: a dokumentumok szűkítése (az epikrízisnél az orvos által kiválasztott leletek)
    public Built build(Patient patient, Predicate<Document> include) {
        Long patientId = patient.getId();
        LocalDate today = LocalDate.now(CLINICAL_ZONE);

        // csak a feldolgozott, típussal és dátummal rendelkező dokumentumok (a többi nem helyezhető idővonalra)
        List<Document> documents = documentRepository.findByPatientIdOrderByUploadedAtDesc(patientId).stream()
                .filter(d -> usable(d) && include.test(d))
                .sorted(Comparator.comparing(Document::getClinicalDate).thenComparing(Document::getId))
                .toList();
        Map<Long, DocumentDigest> digests = digestRepository.findByPatientId(patientId).stream()
                .collect(Collectors.toMap(DocumentDigest::getDocumentId, Function.identity()));

        List<Document> textDocuments = documents.stream()
                .filter(d -> d.getType() != DocumentType.LAB && d.getExtractedText() != null)
                .toList();
        List<MedicalHistoryFacts.DocumentInput> documentInputs = textDocuments.stream()
                .map(d -> new MedicalHistoryFacts.DocumentInput(d.getId(), d.getType().name(), d.getClinicalDate(),
                        truncate(mask(patient, d.getExtractedText())), digest(digests.get(d.getId()))))
                .toList();

        // A laborsorok laborletenként; a dokumentum nélküli (kézzel rögzített) sor nem kerül az
        // idővonalra: nincs forrás-dokumentuma, amire az elem mutathatna
        Map<Long, Document> labDocuments = documents.stream()
                .filter(d -> d.getType() == DocumentType.LAB)
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        Map<Long, List<LabResult>> labsByDocument = labResultRepository.findByPatientIdOrderByMeasuredAtDescIdDesc(patientId)
                .stream()
                .filter(r -> r.getDocumentId() != null && labDocuments.containsKey(r.getDocumentId()))
                .collect(Collectors.groupingBy(LabResult::getDocumentId, LinkedHashMap::new, Collectors.toList()));
        List<MedicalHistoryFacts.LabEntry> labEntries = labsByDocument.entrySet().stream()
                .map(e -> labEntry(labDocuments.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparing(MedicalHistoryFacts.LabEntry::date))
                .toList();

        List<SummaryFacts.MedicationFact> medicationFacts = medicationService.findByPatient(patientId, true).stream()
                .map(m -> new SummaryFacts.MedicationFact(m.id(), m.name(), m.activeIngredient(), dose(m.dose(),
                        m.doseUnit()), m.route(), m.verified()))
                .toList();
        List<MedicalHistory.Medication> medications = medicationFacts.stream()
                .map(m -> new MedicalHistory.Medication(m.id(), m.name(), m.dose(), m.route(), m.verified()))
                .toList();
        List<SummaryFacts.AllergyFact> allergies = allergyService.findByPatient(patientId).stream()
                .map(a -> new SummaryFacts.AllergyFact(a.id(), a.substance(), a.verified()))
                .toList();

        Integer age = patient.getBirthDate() == null ? null : Period.between(patient.getBirthDate(), today).getYears();
        // Az összefoglaló a Klinikai státuszéval azonos módon készül: az elmúlt 6 hónap tényeiből (a
        // javaslatok nélkül); az idővonal és a diagnózisok a teljes előzményből
        SummaryFactsBuilder.Built summary = summaryFactsBuilder.build(patient, today.minusMonths(SUMMARY_MONTHS), today);
        MedicalHistoryFacts facts = new MedicalHistoryFacts(today, age, documentInputs, labEntries, medicationFacts,
                allergies, summary.facts());

        Map<String, List<Long>> ids = new LinkedHashMap<>();
        // a modellnek átadott dokumentumok: a szöveges leletek + a laborsort tartalmazó laborleletek
        ids.put("documentIds", Stream.concat(textDocuments.stream().map(Document::getId),
                labsByDocument.keySet().stream()).sorted().toList());
        ids.put("labResultIds", labsByDocument.values().stream().flatMap(List::stream).map(LabResult::getId).toList());
        ids.put("medicationIds", medicationFacts.stream().map(SummaryFacts.MedicationFact::id).toList());
        ids.put("allergyIds", allergies.stream().map(SummaryFacts.AllergyFact::id).toList());
        // az összefoglaló (6 hónap) bemenetéből, ami a fentiekben nincs benne
        ids.put("statementIds", summary.inputIds().getOrDefault("statementIds", List.of()));
        ids.put("recordingSummaryIds", summary.inputIds().getOrDefault("recordingSummaryIds", List.of()));
        return new Built(facts, medications, ids);
    }

    private static MedicalHistoryFacts.LabEntry labEntry(Document document, List<LabResult> results) {
        List<String> abnormal = results.stream()
                .map(SummaryFactsBuilder::labFact)
                .filter(f -> f.status() != null)
                .sorted(Comparator.comparing(SummaryFacts.LabFact::name, String.CASE_INSENSITIVE_ORDER))
                .map(f -> f.name() + ": " + f.value() + (f.unit() == null ? "" : " " + f.unit())
                        + (f.reference() == null ? "" : " (ref " + f.reference() + ")") + " [" + f.status() + "]")
                .toList();
        return new MedicalHistoryFacts.LabEntry(document.getId(), document.getClinicalDate(), abnormal, results.size());
    }

    private static MedicalHistoryFacts.Digest digest(DocumentDigest d) {
        if (d == null) {
            return null;
        }
        return new MedicalHistoryFacts.Digest(d.getTitle(), d.getPlace(), d.getSummary(), d.getQuote(), d.getEvents(),
                d.getDiagnoses(), d.getWarnings(), d.getPromptHash());
    }

    private static String dose(BigDecimal dose, String unit) {
        if (dose == null) {
            return null;
        }
        String n = dose.stripTrailingZeros().toPlainString().replace('.', ',');
        return Objects.requireNonNullElse(unit, "").isBlank() ? n : n + " " + unit;
    }

    // A beteg neve, anyja neve és TAJ-száma "[beteg]" / "[TAJ]" lesz a modellnek átadott szövegben: a kis
    // modell a kivonatba írta a nevet ("… idegenfogára készítettek …"). A kivonat idézetei ehhez a
    // (maszkolt) szöveghez igazodnak — karakterpozícióra a kivonat nem hivatkozik.
    public static String mask(Patient patient, String text) {
        String out = text;
        if (patient.getTaj() != null && patient.getTaj().length() == 9) {
            String t = patient.getTaj();
            out = out.replaceAll("(?<!\\d)" + t.substring(0, 3) + "[\\s-]?" + t.substring(3, 6) + "[\\s-]?"
                    + t.substring(6) + "(?!\\d)", "[TAJ]");
        }
        for (String name : new String[] {patient.getName(), patient.getMotherName()}) {
            if (name == null) {
                continue;
            }
            for (String part : name.strip().split("[\\s.,-]+")) {
                if (part.length() >= 3) {
                    out = out.replaceAll("(?iu)(?<!\\p{L})" + Pattern.quote(part) + "(?!\\p{L})", "[beteg]");
                }
            }
        }
        return out;
    }

    private static String truncate(String text) {
        return text.length() > MAX_DOCUMENT_CHARS ? text.substring(0, MAX_DOCUMENT_CHARS) : text;
    }
}
