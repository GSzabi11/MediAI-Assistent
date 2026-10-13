package bme.mediai.utterance.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Nincs módosító metódus: az allitas.forras turn_id-vel hivatkozik a
// megszólalásokra, egy utólagos módosítás a provenance-t hamisítaná.
@Entity
@Table(name = "utterance")
public class Utterance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long recordingId;

    private Integer turnId;

    @Enumerated(EnumType.STRING)
    private Speaker speaker;

    private Float similarity;

    private Integer startMs;

    private Integer endMs;

    private String text;

    private Float asrConfidence;

    // AI-javítás (V27): az eredeti ASR-szöveg / -beszélő, ha a modell javította
    private String asrText;

    @Enumerated(EnumType.STRING)
    private Speaker asrSpeaker;

    private String correctedBy;

    private String correctionReason;

    private String reviewNote;

    protected Utterance() {
    }

    public Utterance(Long recordingId, Integer turnId, Speaker speaker, Float similarity,
            Integer startMs, Integer endMs, String text, Float asrConfidence) {
        this.recordingId = recordingId;
        this.turnId = turnId;
        this.speaker = speaker;
        this.similarity = similarity;
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
        this.asrConfidence = asrConfidence;
    }

    // A hangszolgáltatás eredménye: text/speaker a megjelenített változat; ha a modell javította,
    // az eredeti az asrText/asrSpeaker-ben (csak az, ami eltér), a javító modellel és az okkal
    public static Utterance reviewed(Long recordingId, Integer turnId, Speaker speaker, Float similarity,
            Integer startMs, Integer endMs, String text, String asrText, Speaker asrSpeaker, String correctedBy,
            String correctionReason, String reviewNote) {
        Utterance u = new Utterance(recordingId, turnId, speaker, similarity, startMs, endMs, text, null);
        u.reviewNote = reviewNote == null || reviewNote.isBlank() ? null : reviewNote.strip();
        boolean textChanged = asrText != null && !asrText.equals(text);
        boolean speakerChanged = asrSpeaker != null && asrSpeaker != speaker;
        if (textChanged || speakerChanged) {
            if (correctedBy == null || correctedBy.isBlank()) {
                throw new IllegalArgumentException("A(z) " + turnId + " javított megszólalásnál hiányzik a javító modell");
            }
            u.asrText = textChanged ? asrText : null;
            u.asrSpeaker = speakerChanged ? asrSpeaker : null;
            u.correctedBy = correctedBy;
            u.correctionReason = correctionReason;
        }
        return u;
    }

    public Long getId() {
        return id;
    }

    public Long getRecordingId() {
        return recordingId;
    }

    public Integer getTurnId() {
        return turnId;
    }

    public Speaker getSpeaker() {
        return speaker;
    }

    public Float getSimilarity() {
        return similarity;
    }

    public Integer getStartMs() {
        return startMs;
    }

    public Integer getEndMs() {
        return endMs;
    }

    public String getText() {
        return text;
    }

    public Float getAsrConfidence() {
        return asrConfidence;
    }

    public String getAsrText() {
        return asrText;
    }

    public Speaker getAsrSpeaker() {
        return asrSpeaker;
    }

    public String getCorrectedBy() {
        return correctedBy;
    }

    public String getCorrectionReason() {
        return correctionReason;
    }

    public String getReviewNote() {
        return reviewNote;
    }
}
