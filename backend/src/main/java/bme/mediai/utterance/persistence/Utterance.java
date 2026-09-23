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
}
