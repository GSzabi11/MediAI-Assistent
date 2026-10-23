package bme.mediai.research.persistence;

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

// Egy keresés (V30). A kontextus szövege, a kérés és a keresőkifejezés pillanatkép: ez fut le, és
// ehhez méri a modell a relevanciát — a kontextus későbbi szerkesztése nem írja át.
@Entity
@Table(name = "research")
public class Research {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private Long patientId;

    @Column(updatable = false)
    private Long contextId;

    @Column(updatable = false)
    private Long requestedBy;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Enumerated(EnumType.STRING)
    private ResearchStatus status;

    private String progress;

    private OffsetDateTime completedAt;

    private String error;

    @Column(updatable = false)
    private String contextText;

    @Column(updatable = false)
    private String extraPrompt;

    @Column(updatable = false)
    private String query;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<ResearchSource> sources;

    @Column(updatable = false)
    private int maxResults;

    private Integer totalFound;

    protected Research() {
    }

    public Research(Long patientId, Long contextId, Long requestedBy, String contextText, String extraPrompt,
            String query, List<ResearchSource> sources, int maxResults) {
        this.patientId = patientId;
        this.contextId = contextId;
        this.requestedBy = requestedBy;
        this.contextText = contextText;
        this.extraPrompt = extraPrompt;
        this.query = query;
        this.sources = List.copyOf(sources);
        this.maxResults = maxResults;
        this.status = ResearchStatus.SEARCHING;
        this.progress = "Sorban áll…";
    }

    public void progress(String message) {
        if (status == ResearchStatus.SEARCHING) {
            this.progress = message;
        }
    }

    public void complete(int totalFound) {
        if (status != ResearchStatus.SEARCHING) {
            throw new IllegalStateException("A(z) " + id + " keresés eredménye már megérkezett");
        }
        this.totalFound = totalFound;
        this.status = ResearchStatus.DONE;
        this.progress = null;
        this.completedAt = OffsetDateTime.now();
    }

    public void fail(String error) {
        if (status != ResearchStatus.SEARCHING) {
            return;
        }
        this.error = error;
        this.status = ResearchStatus.FAILED;
        this.progress = null;
        this.completedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Long getContextId() {
        return contextId;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public ResearchStatus getStatus() {
        return status;
    }

    public String getProgress() {
        return progress;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public String getError() {
        return error;
    }

    public String getContextText() {
        return contextText;
    }

    public String getExtraPrompt() {
        return extraPrompt;
    }

    public String getQuery() {
        return query;
    }

    public List<ResearchSource> getSources() {
        return sources;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public Integer getTotalFound() {
        return totalFound;
    }
}
