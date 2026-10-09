package bme.mediai.chat.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import bme.mediai.statement.persistence.Uncertainty;

// Nem entitás: a beszélgetés nem tárolódik (V25 óta nincs chat_message tábla), csak a
// ChatMessageStore tartja memóriában, amíg a válasz elkészül és a felület lekérdezi.
// A kérdés, az előzmény és a kontextus létrehozáskor rögzül. Az eredményt csak a
// PROCESSING állapotú üzenet kaphatja meg, egyszer — a service az üzenetre szinkronizálva
// módosítja, így két párhuzamos callback sem írhatja felül egymást.
public class ChatMessage {

    private final Long id;
    private final Long patientId;
    private final Long requestedBy;
    private final OffsetDateTime requestedAt;
    private ChatStatus status;
    private OffsetDateTime completedAt;
    private String error;
    private final String question;
    private final List<Long> history;
    private final List<ChatContextItem> context;
    private List<ChatContextItem> retrieved;
    private List<ChatPoint> answer;
    private Uncertainty uncertainty;
    private List<String> warnings;
    private String modelName;
    private Long seed;
    private String promptHash;
    private String extractorVersion;
    private String embeddingModel;

    public ChatMessage(Long id, Long patientId, Long requestedBy, String question, List<Long> history,
            List<ChatContextItem> context) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("A kérdés nem lehet üres");
        }
        this.id = id;
        this.patientId = patientId;
        this.requestedBy = requestedBy;
        this.requestedAt = OffsetDateTime.now();
        this.status = ChatStatus.PROCESSING;
        this.question = question.strip();
        this.history = List.copyOf(history);
        this.context = List.copyOf(context);
    }

    // A Python a feldolgozás közben kéri le a kérdéshez illő darabokat; csak a legutóbbi számít
    public void recordRetrieval(List<ChatContextItem> chunks, String embeddingModel) {
        requireProcessing();
        this.retrieved = List.copyOf(chunks);
        this.embeddingModel = embeddingModel;
    }

    public void complete(List<ChatPoint> answer, Uncertainty uncertainty, List<String> warnings, String modelName,
            Long seed, String promptHash, String extractorVersion) {
        requireProcessing();
        if (answer.isEmpty() || uncertainty == null || modelName == null) {
            throw new IllegalArgumentException("Kész válaszhoz kell legalább egy pont, bizonytalanság és modell");
        }
        this.status = ChatStatus.DONE;
        this.completedAt = OffsetDateTime.now();
        this.answer = List.copyOf(answer);
        this.uncertainty = uncertainty;
        this.warnings = List.copyOf(warnings);
        this.modelName = modelName;
        this.seed = seed;
        this.promptHash = promptHash;
        this.extractorVersion = extractorVersion;
    }

    public void fail(String error) {
        requireProcessing();
        this.status = ChatStatus.FAILED;
        this.completedAt = OffsetDateTime.now();
        this.error = error;
    }

    private void requireProcessing() {
        if (status != ChatStatus.PROCESSING) {
            throw new IllegalStateException("A(z) " + id + " chat-válasz már lezárult (" + status + ")");
        }
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public OffsetDateTime getRequestedAt() {
        return requestedAt;
    }

    public ChatStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public String getError() {
        return error;
    }

    public String getQuestion() {
        return question;
    }

    public List<Long> getHistory() {
        return history;
    }

    public List<ChatContextItem> getContext() {
        return context;
    }

    public List<ChatContextItem> getRetrieved() {
        return retrieved == null ? List.of() : retrieved;
    }

    public List<ChatPoint> getAnswer() {
        return answer;
    }

    public Uncertainty getUncertainty() {
        return uncertainty;
    }

    public List<String> getWarnings() {
        return warnings == null ? List.of() : warnings;
    }

    public String getModelName() {
        return modelName;
    }

    public Long getSeed() {
        return seed;
    }

    public String getPromptHash() {
        return promptHash;
    }

    public String getExtractorVersion() {
        return extractorVersion;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }
}
