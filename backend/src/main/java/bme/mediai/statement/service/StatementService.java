package bme.mediai.statement.service;

import bme.mediai.document.exception.DocumentNotFoundException;
import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentRepository;
import bme.mediai.statement.api.StatementCreateRequest;
import bme.mediai.statement.api.StatementResponse;
import bme.mediai.statement.exception.StatementNotFoundException;
import bme.mediai.statement.exception.StatementSourcePatientMismatchException;
import bme.mediai.statement.persistence.Statement;
import bme.mediai.statement.persistence.StatementApproval;
import bme.mediai.statement.persistence.StatementApprovalRepository;
import bme.mediai.statement.persistence.StatementProducer;
import bme.mediai.statement.persistence.StatementRepository;
import bme.mediai.statement.persistence.StatementSource;
import bme.mediai.statement.persistence.StatementType;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.PatientRepository;
import bme.mediai.recording.exception.RecordingNotFoundException;
import bme.mediai.recording.persistence.Recording;
import bme.mediai.recording.persistence.RecordingRepository;
import bme.mediai.utterance.persistence.Utterance;
import bme.mediai.utterance.persistence.UtteranceRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StatementService {

    private final StatementRepository repository;
    private final PatientRepository patientRepository;
    private final RecordingRepository recordingRepository;
    private final UtteranceRepository utteranceRepository;
    private final StatementApprovalRepository approvalRepository;
    private final DocumentRepository documentRepository;

    // A dokumentum klinikai dátuma (DATE) ebben a zónában éjfélkor lesz clinicalTime
    private static final ZoneId CLINICAL_ZONE = ZoneId.of("Europe/Budapest");

    public StatementService(StatementRepository repository, PatientRepository patientRepository,
            RecordingRepository recordingRepository, UtteranceRepository utteranceRepository,
            StatementApprovalRepository approvalRepository, DocumentRepository documentRepository) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.recordingRepository = recordingRepository;
        this.utteranceRepository = utteranceRepository;
        this.approvalRepository = approvalRepository;
        this.documentRepository = documentRepository;
    }

    // Kívülről (Python, frontend) csak forrás- és modell-állítás jöhet: ha a kliens
    // DETERMINISTIC_RULE-t küldhetne, egy LLM-kimenet szabálynak álcázhatná magát.
    @Transactional
    public StatementResponse createFromApi(StatementCreateRequest request) {
        if (request.type() == StatementType.DETERMINISTIC_RULE) {
            throw new IllegalArgumentException(
                    "Szabály-alapú állítást csak a szabálymotor hozhat létre, az API nem");
        }
        requirePatientExists(request.patientId());

        StatementProducer producer = request.producer();
        OffsetDateTime clinicalTime = request.clinicalTime();

        request.source().validate();
        boolean retrievedFact = request.type() == StatementType.RETRIEVED_FACT;

        switch (request.source()) {
            case StatementSource.Audio audio -> {
                Recording recording = requireRecordingOfPatient(audio.recordingId(), request.patientId());
                List<Utterance> utterances = requireUtterances(audio);
                if (retrievedFact) {
                    requireVerbatim(request.text(), utterances);
                }
                producer = producer.withAsrModel(recording.getAsrModel());
                if (clinicalTime == null) {
                    clinicalTime = recording.getRecordedAt();
                }
            }
            case StatementSource.Document doc -> {
                Document document = requireDocumentOfPatient(doc.documentId(), request.patientId());
                // CLAUDE.md: char-offsettel csak kinyert szöveggel rendelkező dokumentumra (különben 409)
                String span = document.textSpan(doc.charStart(), doc.charEnd());
                if (retrievedFact && !normalize(span).equals(normalize(request.text()))) {
                    throw new IllegalArgumentException(
                            "RETRIEVED_FACT szövege nem egyezik a hivatkozott szövegrésszel (\"" + span + "\"); "
                            + "ha átfogalmazott, MODEL_INFERENCE-ként kell rögzíteni");
                }
                if (clinicalTime == null) {
                    clinicalTime = clinicalTimeOf(document);
                }
            }
            case StatementSource.Lab lab -> throw new IllegalArgumentException(
                    "LAB forrású állítást csak a szabálymotor hozhat létre, az API nem");
            case StatementSource.Image image -> {
                Document document = requireDocumentOfPatient(image.documentId(), request.patientId());
                if (!document.isImageLike()) {
                    throw new IllegalArgumentException(
                            "IMAGE forrás csak kép vagy PDF dokumentumra mutathat, ez: " + document.getMimeType());
                }
                if (clinicalTime == null) {
                    clinicalTime = clinicalTimeOf(document);
                }
            }
        }

        Statement statement = new Statement(
                request.patientId(),
                request.type(),
                request.text(),
                request.label(),
                request.severity(),
                request.uncertainty(),
                request.source(),
                producer,
                clinicalTime);

        return StatementResponse.from(repository.save(statement), null);
    }

    @Transactional(readOnly = true)
    public StatementResponse findById(Long id) {
        Statement statement = repository.findById(id)
                .orElseThrow(() -> new StatementNotFoundException(id));
        StatementApproval latest = approvalRepository.findFirstByStatementIdOrderByDecidedAtDescIdDesc(id)
                .orElse(null);
        return StatementResponse.from(statement, latest);
    }

    @Transactional(readOnly = true)
    public List<StatementResponse> findByPatient(Long patientId) {
        requirePatientExists(patientId);
        List<Statement> statements = repository.findByPatientIdOrderByClinicalTimeDesc(patientId);
        if (statements.isEmpty()) {
            return List.of();
        }
        Map<Long, StatementApproval> latestByStatement = approvalRepository
                .findLatestForStatements(statements.stream().map(Statement::getId).toList())
                .stream()
                .collect(Collectors.toMap(StatementApproval::getStatementId, Function.identity()));
        return statements.stream()
                .map(s -> StatementResponse.from(s, latestByStatement.get(s.getId())))
                .toList();
    }

    private Recording requireRecordingOfPatient(Long recordingId, Long patientId) {
        Recording recording = recordingRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingNotFoundException(recordingId));
        if (!recording.getPatientId().equals(patientId)) {
            throw new StatementSourcePatientMismatchException(
                    "felvétel " + recordingId, recording.getPatientId(), patientId);
        }
        return recording;
    }

    private Document requireDocumentOfPatient(Long documentId, Long patientId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!document.getPatientId().equals(patientId)) {
            throw new StatementSourcePatientMismatchException(
                    "dokumentum " + documentId, document.getPatientId(), patientId);
        }
        return document;
    }

    private static OffsetDateTime clinicalTimeOf(Document document) {
        if (document.getClinicalDate() == null) {
            throw new IllegalArgumentException("A clinicalTime kötelező: a(z) " + document.getId()
                    + " dokumentumnak nincs klinikai dátuma, amiből át lehetne venni");
        }
        return document.getClinicalDate().atStartOfDay(CLINICAL_ZONE).toOffsetDateTime();
    }

    // A source JSONB-n nincs FK (schema-decisions.md), a turn_id-k létezését itt kell ellenőrizni.
    private List<Utterance> requireUtterances(StatementSource.Audio audio) {
        List<Utterance> found = utteranceRepository
                .findByRecordingIdAndTurnIdInOrderByTurnId(audio.recordingId(), audio.turnIds());
        if (found.size() != audio.turnIds().size()) {
            List<Integer> existing = found.stream().map(Utterance::getTurnId).toList();
            List<Integer> missing = audio.turnIds().stream().filter(t -> !existing.contains(t)).toList();
            throw new IllegalArgumentException(
                    "Nem létező turnId-k a(z) " + audio.recordingId() + " felvételben: " + missing);
        }
        return found;
    }

    // RETRIEVED_FACT = szó szerint a forrásból. Ha a szöveg nincs benne a hivatkozott
    // megszólalásokban, akkor az modell-következtetés, nem forrás.
    private void requireVerbatim(String text, List<Utterance> utterances) {
        String sourceText = utterances.stream()
                .map(Utterance::getText)
                .collect(Collectors.joining(" "));
        if (!normalize(sourceText).contains(normalize(text))) {
            throw new IllegalArgumentException(
                    "RETRIEVED_FACT szövege nem szerepel szó szerint a hivatkozott megszólalásokban; "
                    + "ha átfogalmazott, MODEL_INFERENCE-ként kell rögzíteni");
        }
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private void requirePatientExists(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
    }
}
