package bme.mediai.chat.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import bme.mediai.statement.persistence.Uncertainty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// A kérdés, az előzmény és a kontextus létrehozáskor rögzül. Az eredményt csak a
// PROCESSING állapotú sor kaphatja meg, egyszer — a service sorzárral (FOR UPDATE) olvassa be,
// így két párhuzamos callback sem írhatja felül egymást.
@Entity
@Table(name = "chat_message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private Long patientId;

    @Column(updatable = false)
    private Long requestedBy;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Enumerated(EnumType.STRING)
    private ChatStatus status;

    // a lezárás pillanata (az alkalmazás órája — a requested_at a DB-é)
    @Column(insertable = false)
    private OffsetDateTime completedAt;

    private String error;

    @Column(updatable = false)
    private String question;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<Long> history;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<ChatContextItem> context;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<ChatContextItem> retrieved;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<ChatPoint> answer;

    @Enumerated(EnumType.STRING)
    private Uncertainty uncertainty;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> warnings;

    private String modelName;

    private Long seed;

    private String promptHash;

    private String extractorVersion;

    private String embeddingModel;

    protected ChatMessage() {
    }

    public ChatMessage(Long patientId, Long requestedBy, String question, List<Long> history,
            List<ChatContextItem> context) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("A kérdés nem lehet üres");
        }
        this.patientId = patientId;
        this.requestedBy = requestedBy;
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
