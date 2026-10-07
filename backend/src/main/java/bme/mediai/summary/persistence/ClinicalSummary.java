package bme.mediai.summary.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

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

// A létrehozáskor rögzített mezők (beteg, kérő, időszak, bemenet) nem változnak; az eredményt
// kizárólag a ClinicalSummaryRepository atomikus lekérdezései írják (csak PROCESSING-ből).
@Entity
@Table(name = "clinical_summary")
public class ClinicalSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long patientId;

    private Long requestedBy;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private SummaryStatus status;

    @Column(insertable = false, updatable = false)
    private OffsetDateTime completedAt;

    @Column(insertable = false, updatable = false)
    private String error;

    private LocalDate periodStart;

    private LocalDate periodEnd;

    @Column(insertable = false, updatable = false)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(insertable = false, updatable = false)
    private List<String> recommendations;

    // a modellnek átadott adatok azonosítói típusonként
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private Map<String, List<Long>> input;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(insertable = false, updatable = false)
    private List<String> warnings;

    @Column(insertable = false, updatable = false)
    private String modelName;

    @Column(insertable = false, updatable = false)
    private Long seed;

    @Column(insertable = false, updatable = false)
    private String promptHash;

    @Column(insertable = false, updatable = false)
    private String extractorVersion;

    protected ClinicalSummary() {
    }

    public ClinicalSummary(Long patientId, Long requestedBy, LocalDate periodStart, LocalDate periodEnd,
            Map<String, List<Long>> input) {
        if (periodStart.isAfter(periodEnd)) {
            throw new IllegalArgumentException("Az időszak kezdete nem lehet a vége után");
        }
        this.patientId = patientId;
        this.requestedBy = requestedBy;
        this.status = SummaryStatus.PROCESSING;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.input = input;
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

    public SummaryStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public String getError() {
        return error;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public String getSummary() {
        return summary;
    }

    public List<String> getRecommendations() {
        return recommendations;
    }

    public Map<String, List<Long>> getInput() {
        return input;
    }

    public List<String> getWarnings() {
        return warnings;
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
}
