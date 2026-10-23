package bme.mediai.recordingfinding.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Egy kinyerési futás (V34): PROCESSING-ként jön létre, az eredményt egyszer kapja meg (DONE / FAILED).
// HOGY: modell + seed + prompt-hash; az elemek a recording_finding táblában, extraction_id-vel.
@Entity
@Table(name = "recording_extraction")
public class RecordingExtraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long recordingId;

    private Long requestedBy;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Enumerated(EnumType.STRING)
    private RecordingExtractionStatus status;

    private OffsetDateTime completedAt;

    private String error;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> warnings;

    private String modelName;

    private Long seed;

    private String promptHash;

    protected RecordingExtraction() {
    }

    public RecordingExtraction(Long recordingId, Long requestedBy) {
        this.recordingId = recordingId;
        this.requestedBy = requestedBy;
        this.status = RecordingExtractionStatus.PROCESSING;
    }

    public void complete(List<String> warnings, String modelName, Long seed, String promptHash) {
        if (status != RecordingExtractionStatus.PROCESSING) {
            throw new IllegalStateException("A(z) " + id + " kinyerés eredménye már megérkezett");
        }
        this.warnings = List.copyOf(warnings);
        this.modelName = modelName;
        this.seed = seed;
        this.promptHash = promptHash;
        this.status = RecordingExtractionStatus.DONE;
        this.completedAt = OffsetDateTime.now();
    }

    public void fail(String error) {
        if (status != RecordingExtractionStatus.PROCESSING) {
            return;
        }
        this.error = error;
        this.status = RecordingExtractionStatus.FAILED;
        this.completedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getRecordingId() {
        return recordingId;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public OffsetDateTime getRequestedAt() {
        return requestedAt;
    }

    public RecordingExtractionStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public String getError() {
        return error;
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
}
