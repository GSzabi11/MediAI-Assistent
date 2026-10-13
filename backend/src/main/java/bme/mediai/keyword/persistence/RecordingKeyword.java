package bme.mediai.keyword.persistence;

import java.time.OffsetDateTime;

import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// A felvétel kulcsszava (V28). Az AI javasolja (MODEL → SUGGESTED, az orvos dönt róla), vagy az
// orvos adja hozzá a + gombbal (DOCTOR → rögtön ACCEPTED). A kifejezés nem változik; elutasított
// javaslat megmarad (látszik, mit javasolt a modell), csak az összefoglalóba nem kerül.
@Entity
@Table(name = "recording_keyword")
public class RecordingKeyword {

    public static final int MAX_TERM_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long recordingId;

    private String term;

    @Enumerated(EnumType.STRING)
    private KeywordOrigin origin;

    @Enumerated(EnumType.STRING)
    private KeywordStatus status;

    private String modelName;

    private Long userId;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime decidedAt;

    protected RecordingKeyword() {
    }

    private RecordingKeyword(Long recordingId, String term, KeywordOrigin origin, KeywordStatus status,
            String modelName, Long userId) {
        String t = term == null ? "" : term.strip().replaceAll("\\s+", " ");
        if (t.isEmpty() || t.length() > MAX_TERM_LENGTH) {
            throw new IllegalArgumentException("A kulcsszó 1–" + MAX_TERM_LENGTH + " karakter lehet");
        }
        this.recordingId = recordingId;
        this.term = t;
        this.origin = origin;
        this.status = status;
        this.modelName = modelName;
        this.userId = userId;
    }

    public static RecordingKeyword suggestedByModel(Long recordingId, String term, String modelName) {
        return new RecordingKeyword(recordingId, term, KeywordOrigin.MODEL, KeywordStatus.SUGGESTED, modelName, null);
    }

    public static RecordingKeyword addedByDoctor(Long recordingId, String term, Long userId) {
        return new RecordingKeyword(recordingId, term, KeywordOrigin.DOCTOR, KeywordStatus.ACCEPTED, null, userId);
    }

    /** Az orvos döntése egy AI-javaslatról (egyszer). */
    public void decide(KeywordStatus decision, Long userId) {
        if (origin != KeywordOrigin.MODEL || status != KeywordStatus.SUGGESTED) {
            throw new IllegalStateException("Csak még el nem döntött AI-javaslatról lehet dönteni");
        }
        if (decision == KeywordStatus.SUGGESTED) {
            throw new IllegalArgumentException("A döntés ACCEPTED vagy REJECTED lehet");
        }
        this.status = decision;
        this.userId = userId;
        this.decidedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getRecordingId() {
        return recordingId;
    }

    public String getTerm() {
        return term;
    }

    public KeywordOrigin getOrigin() {
        return origin;
    }

    public KeywordStatus getStatus() {
        return status;
    }

    public String getModelName() {
        return modelName;
    }

    public Long getUserId() {
        return userId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }
}
