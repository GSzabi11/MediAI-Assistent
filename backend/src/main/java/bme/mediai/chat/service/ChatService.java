package bme.mediai.chat.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bme.mediai.chat.api.ChatFact;
import bme.mediai.chat.api.ChatJobRequest;
import bme.mediai.chat.api.ChatMessageResponse;
import bme.mediai.chat.api.ChatResultRequest;
import bme.mediai.chat.api.ChatRetrieveRequest;
import bme.mediai.chat.api.ChatRetrieveResponse;
import bme.mediai.chat.exception.ChatConflictException;
import bme.mediai.chat.exception.ChatMessageNotFoundException;
import bme.mediai.chat.persistence.ChatCitation;
import bme.mediai.chat.persistence.ChatContextItem;
import bme.mediai.chat.persistence.ChatMessage;
import bme.mediai.chat.persistence.ChatMessageStore;
import bme.mediai.chat.persistence.ChatPoint;
import bme.mediai.chat.persistence.ChatStatus;
import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentChunk;
import bme.mediai.document.persistence.DocumentChunkRepository;
import bme.mediai.document.persistence.DocumentRepository;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.Patient;
import bme.mediai.patient.persistence.PatientRepository;
import bme.mediai.rule.service.LabReferenceRangeRule;
import bme.mediai.statement.persistence.Statement;
import bme.mediai.statement.persistence.StatementRepository;
import bme.mediai.statement.persistence.StatementSource;
import bme.mediai.statement.persistence.Uncertainty;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.UserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

// Klinikai asszisztens (RAG-chat). A Spring állítja össze a kontextust, végzi a vektoros
// keresést (pgvector) és ellenőrzi a választ; a beágyazást és az Ollama-hívást a Python végzi
// (a Spring sosem hívja az Ollamát). Nem statement, és nem is tárolódik: az üzenetek csak
// memóriában élnek (ChatMessageStore), amíg a válasz elkészül — a felhasználó döntése
// (docs/clinical-chat.md).
@Service
public class ChatService {

    // ennyi dokumentum-darab megy a modellnek kérdésenként (~800 karakter / darab)
    static final int RETRIEVED_CHUNKS = 8;
    // ennél közelebbi (koszinusz-távolság) darab egy újabb dokumentumban = sablonszöveg ismétlődése
    static final double DUPLICATE_DISTANCE = 0.05;
    private static final int MAX_ERROR_LENGTH = 2000;

    private final ChatMessageStore store;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final ChatContextBuilder contextBuilder;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final StatementRepository statementRepository;
    private final ApplicationEventPublisher events;
    private final Validator validator;
    private final String callbackBaseUrl;

    public ChatService(ChatMessageStore store, PatientRepository patientRepository,
            UserRepository userRepository, ChatContextBuilder contextBuilder, DocumentChunkRepository chunkRepository,
            DocumentRepository documentRepository, StatementRepository statementRepository,
            ApplicationEventPublisher events, Validator validator,
            @Value("${mediai.ai-service.callback-base-url}") String callbackBaseUrl) {
        this.store = store;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.contextBuilder = contextBuilder;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.statementRepository = statementRepository;
        this.events = events;
        this.validator = validator;
        this.callbackBaseUrl = callbackBaseUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public ChatMessageResponse ask(Long patientId, Long userId, String question, List<Long> previousMessageIds) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new PatientNotFoundException(patientId));
        if (userId != null && !userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
        List<ChatMessage> history = loadHistory(patientId, previousMessageIds == null ? List.of() : previousMessageIds);
        ChatContextBuilder.Built context = contextBuilder.build(patient);
        List<Long> historyIds = history.stream().map(ChatMessage::getId).toList();

        // betegenként egyszerre egy készülő válasz (dupla küldés ellen)
        ChatMessage saved = store.addIfIdle(patientId,
                        id -> new ChatMessage(id, patientId, userId, question, historyIds, context.citable()))
                .orElseThrow(() -> new ChatConflictException("A betegről már készül egy válasz — várd meg"));
        Long id = saved.getId();
        ChatJobRequest job = new ChatJobRequest(id,
                callbackBaseUrl + "/api/chat-messages/" + id + "/result",
                callbackBaseUrl + "/api/chat-messages/" + id + "/retrieve",
                saved.getQuestion(),
                history.stream().map(h -> new ChatJobRequest.HistoryTurn(h.getQuestion(), answerText(h))).toList(),
                context.facts());
        events.publishEvent(new ChatRequestedEvent(id, job));
        return ChatMessageResponse.from(saved, List.of());
    }

    @Transactional(readOnly = true)
    public ChatMessageResponse findById(Long id) {
        ChatMessage m = find(id);
        synchronized (m) {
            return ChatMessageResponse.from(m, relatedRules(m, ruleStatementsByLabResult(m.getPatientId())));
        }
    }

    // a hozzáférési naplónak: melyik beteg kérdése (ismeretlen/lejárt azonosítónál üres)
    public Long patientIdOf(Long id) {
        return store.findById(id).map(ChatMessage::getPatientId).orElse(null);
    }

    // Python → Spring a feldolgozás közben: a kérdés vektorához legközelebbi darabok a beteg
    // dokumentumaiból. Ami itt visszamegy, az kerül a retrieved-be — a válasz csak ezekre és a
    // context elemeire hivatkozhat.
    @Transactional(readOnly = true)
    public ChatRetrieveResponse retrieve(Long id, ChatRetrieveRequest request) {
        ChatMessage message = find(id);
        requireProcessing(message);
        validate(request);
        float[] embedding = request.embedding();
        if (embedding.length != DocumentChunk.EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException("Az embedding " + DocumentChunk.EMBEDDING_DIMENSION
                    + " dimenziós legyen (kapott: " + embedding.length + ")");
        }
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (!Float.isFinite(embedding[i])) {
                throw new IllegalArgumentException("Az embedding nem véges értéket tartalmaz");
            }
            literal.append(i == 0 ? "" : ",").append(embedding[i]);
        }
        literal.append(']');

        List<DocumentChunkRepository.NearestChunk> nearest = chunkRepository.findNearestForPatient(
                message.getPatientId(), request.embeddingModel(), literal.toString(), DUPLICATE_DISTANCE,
                RETRIEVED_CHUNKS);
        Map<Long, Document> documents = documentRepository.findAllById(
                nearest.stream().map(DocumentChunkRepository.NearestChunk::getDocumentId).distinct().toList())
                .stream().collect(Collectors.toMap(Document::getId, Function.identity()));

        List<ChatFact> chunks = new ArrayList<>();
        for (DocumentChunkRepository.NearestChunk c : nearest) {
            Document d = documents.get(c.getDocumentId());
            chunks.add(new ChatFact(ChatContextBuilder.CHUNK, c.getId(), ChatContextBuilder.documentLabel(d),
                    c.getText(), new StatementSource.Document(d.getId(), c.getCharStart(), c.getCharEnd())));
        }
        // az üzenetre szinkronizálva: a callbackek egymás után, a friss állapotot látva írnak
        synchronized (message) {
            requireProcessing(message);
            message.recordRetrieval(chunks.stream()
                    .map(f -> new ChatContextItem(f.kind(), f.id(), f.label(), f.source())).toList(),
                    request.embeddingModel());
        }
        return new ChatRetrieveResponse(chunks);
    }

    // Python callback. Az érvénytelen eredmény is FAILED-del zárja (a controller hívja a fail-t)
    @Transactional(readOnly = true)
    public ChatMessageResponse complete(Long id, ChatResultRequest result) {
        ChatMessage message = find(id);
        synchronized (message) {
            requireProcessing(message);
            validate(result);
            if ("FAILED".equals(result.status())) {
                message.fail(truncate("AI-feldolgozás: "
                        + (result.error() == null ? "ismeretlen hiba" : result.error())));
                return ChatMessageResponse.from(message, List.of());
            }
            if (result.points() == null || result.points().isEmpty() || result.model() == null) {
                throw new IllegalArgumentException("DONE eredményhez kötelező legalább egy pont és a model");
            }
            // a forrás csak a modellnek átadott tények egyike lehet (a modell nem hivatkozhat máshová)
            Map<String, ChatContextItem> allowed = new HashMap<>();
            for (ChatContextItem item : message.getContext()) {
                allowed.put(item.kind() + ":" + item.id(), item);
            }
            for (ChatContextItem item : message.getRetrieved()) {
                allowed.put(item.kind() + ":" + item.id(), item);
            }
            List<ChatPoint> points = new ArrayList<>();
            for (ChatResultRequest.Point p : result.points()) {
                Set<ChatContextItem> cited = new LinkedHashSet<>();
                for (ChatResultRequest.SourceRef ref : p.sources()) {
                    ChatContextItem item = allowed.get(ref.kind() + ":" + ref.id());
                    if (item == null) {
                        throw new IllegalArgumentException("A válasz a kontextusban nem szereplő forrásra hivatkozik: "
                                + ref.kind() + " #" + ref.id());
                    }
                    cited.add(item);
                }
                points.add(new ChatPoint(p.text().strip(), p.list(), p.heading() && cited.isEmpty(),
                        cited.stream().map(ChatCitation::of).toList()));
            }
            List<String> warnings = result.warnings() == null ? List.of() : result.warnings();
            ChatResultRequest.Model m = result.model();
            message.complete(points, uncertainty(points, warnings), warnings, m.modelName(), m.seed(),
                    m.promptHash(), m.extractorVersion());
            return ChatMessageResponse.from(message,
                    relatedRules(message, ruleStatementsByLabResult(message.getPatientId())));
        }
    }

    public void fail(Long id, String error) {
        store.findById(id).ifPresent(m -> {
            synchronized (m) {
                if (m.getStatus() == ChatStatus.PROCESSING) {
                    m.fail(truncate(error == null || error.isBlank() ? "Ismeretlen hiba" : error.strip()));
                }
            }
        });
    }

    // Determinisztikus, a forrás-lefedettségből (nem a modell önértékelése — a kis modellek
    // magabiztossága rosszul kalibrált): minden tartalmi pontnak van forrása és nincs
    // figyelmeztetés → LOW; legfeljebb a felük forrás nélküli → MEDIUM; különben HIGH.
    static Uncertainty uncertainty(List<ChatPoint> points, List<String> warnings) {
        List<ChatPoint> content = points.stream().filter(p -> !p.heading()).toList();
        if (content.isEmpty()) {
            return Uncertainty.HIGH;
        }
        long uncited = content.stream().filter(p -> p.sources().isEmpty()).count();
        if (uncited == 0 && warnings.isEmpty()) {
            return Uncertainty.LOW;
        }
        return uncited * 2 <= content.size() ? Uncertainty.MEDIUM : Uncertainty.HIGH;
    }

    // A lejárt (ChatMessageStore.RETENTION) előzmény csendben kimarad: a felület egy hosszan nyitva
    // hagyott beszélgetésben is kérdezhet tovább, csak a modell nem látja a régi választ.
    private List<ChatMessage> loadHistory(Long patientId, List<Long> ids) {
        List<ChatMessage> history = store.findAllById(new LinkedHashSet<>(ids));
        for (ChatMessage h : history) {
            if (!h.getPatientId().equals(patientId) || h.getStatus() != ChatStatus.DONE) {
                throw new IllegalArgumentException("Előzmény csak ugyanennek a betegnek egy kész válasza lehet (#"
                        + h.getId() + ")");
            }
        }
        return history.stream()
                .sorted(Comparator.comparing(ChatMessage::getRequestedAt).thenComparing(ChatMessage::getId))
                .toList();
    }

    private static String answerText(ChatMessage m) {
        return m.getAnswer().stream()
                .map(p -> (p.list() ? "- " : "") + p.text())
                .collect(Collectors.joining("\n"));
    }

    // a szabálymotor még érvényes állításai laborsoronként (a hivatkozott mérésekhez)
    private Map<Long, List<Statement>> ruleStatementsByLabResult(Long patientId) {
        return statementRepository.findLabRuleStatements(patientId, LabReferenceRangeRule.RULE_ID).stream()
                .filter(s -> s.getSource() instanceof StatementSource.Lab)
                .collect(Collectors.groupingBy(s -> ((StatementSource.Lab) s.getSource()).labResultId()));
    }

    private static List<ChatMessageResponse.RelatedRule> relatedRules(ChatMessage m,
            Map<Long, List<Statement>> rulesByLab) {
        if (m.getAnswer() == null) {
            return List.of();
        }
        return m.getAnswer().stream()
                .flatMap(p -> p.sources().stream())
                .filter(c -> c.source() instanceof StatementSource.Lab)
                .map(c -> ((StatementSource.Lab) c.source()).labResultId())
                .distinct()
                .flatMap(labId -> rulesByLab.getOrDefault(labId, List.of()).stream())
                .map(s -> new ChatMessageResponse.RelatedRule(s.getId(), s.getText(), s.getSeverity()))
                .toList();
    }

    private ChatMessage find(Long id) {
        return store.findById(id).orElseThrow(() -> new ChatMessageNotFoundException(id));
    }

    private static void requireProcessing(ChatMessage message) {
        if (message.getStatus() != ChatStatus.PROCESSING) {
            throw new ChatConflictException("A(z) " + message.getId() + " chat-válasz már lezárult ("
                    + message.getStatus() + ")");
        }
    }

    private <T> void validate(T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                    .collect(Collectors.joining("; ")));
        }
    }

    private static String truncate(String message) {
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
