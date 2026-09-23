package bme.mediai.statement.service;

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

    public StatementService(StatementRepository repository, PatientRepository patientRepository,
            RecordingRepository recordingRepository, UtteranceRepository utteranceRepository,
            StatementApprovalRepository approvalRepository) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.recordingRepository = recordingRepository;
        this.utteranceRepository = utteranceRepository;
        this.approvalRepository = approvalRepository;
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

        if (request.source() instanceof StatementSource.Audio audio) {
            audio.validate();
            Recording recording = requireRecordingOfPatient(audio.recordingId(), request.patientId());
            List<Utterance> utterances = requireUtterances(audio);
            if (request.type() == StatementType.RETRIEVED_FACT) {
                requireVerbatim(request.text(), utterances);
            }
            producer = producer.withAsrModel(recording.getAsrModel());
            if (clinicalTime == null) {
                clinicalTime = recording.getRecordedAt();
            }
        } else if (clinicalTime == null) {
            throw new IllegalArgumentException(
                    "DOCUMENT/IMAGE forrásnál a clinicalTime kötelező (nincs honnan átvenni)");
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
            throw new StatementSourcePatientMismatchException(recordingId, recording.getPatientId(), patientId);
        }
        return recording;
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
