package bme.mediai.recordingsummary.persistence;

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

// A felvétel összefoglalója (V28). Külön tábla, nem statement (ld. a migrációt). PROCESSING-ként
// jön létre a kéréskor rögzített kulcsszavakkal; az eredményt egyszer kapja meg (DONE / FAILED).
@Entity
@Table(name = "recording_summary")
public class RecordingSummary {

    public record Point(String text, List<Integer> turnIds) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long recordingId;

    private Long requestedBy;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Enumerated(EnumType.STRING)
    private RecordingSummaryStatus status;

    private OffsetDateTime completedAt;

    private String error;

    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Point> points;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<String> keywords;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> warnings;

    private String modelName;

    private Long seed;

    private String promptHash;

    // V29: az orvos szerkesztett változata; az AI eredeti szövege (summary, points) nem változik
    private String editedText;

    private Long editedBy;

    private OffsetDateTime editedAt;

    protected RecordingSummary() {
    }

    public RecordingSummary(Long recordingId, Long requestedBy, List<String> keywords) {
        this.recordingId = recordingId;
        this.requestedBy = requestedBy;
        this.keywords = List.copyOf(keywords);
        this.status = RecordingSummaryStatus.PROCESSING;
    }

    public void complete(String summary, List<Point> points, List<String> warnings, String modelName, Long seed,
            String promptHash) {
        if (status != RecordingSummaryStatus.PROCESSING) {
            throw new IllegalStateException("A(z) " + id + " összefoglaló eredménye már megérkezett");
        }
        this.summary = summary;
        this.points = List.copyOf(points);
        this.warnings = List.copyOf(warnings);
        this.modelName = modelName;
        this.seed = seed;
        this.promptHash = promptHash;
        this.status = RecordingSummaryStatus.DONE;
        this.completedAt = OffsetDateTime.now();
    }

    public void edit(String text, Long userId) {
        if (status != RecordingSummaryStatus.DONE) {
            throw new IllegalStateException("Csak kész összefoglaló szerkeszthető");
        }
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("A szerkesztett összefoglaló nem lehet üres");
        }
        this.editedText = t;
        this.editedBy = userId;
        this.editedAt = OffsetDateTime.now();
    }

    public void fail(String error) {
        if (status != RecordingSummaryStatus.PROCESSING) {
            return;
        }
        this.error = error;
        this.status = RecordingSummaryStatus.FAILED;
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

    public RecordingSummaryStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public String getError() {
        return error;
    }

    public String getSummary() {
        return summary;
    }

    public List<Point> getPoints() {
        return points == null ? List.of() : points;
    }

    public List<String> getKeywords() {
        return keywords == null ? List.of() : keywords;
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

    public String getEditedText() {
        return editedText;
    }

    public Long getEditedBy() {
        return editedBy;
    }

    public OffsetDateTime getEditedAt() {
        return editedAt;
    }
}
