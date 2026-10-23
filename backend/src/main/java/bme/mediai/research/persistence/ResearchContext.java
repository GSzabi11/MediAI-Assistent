package bme.mediai.research.persistence;

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

// A beteg állapotának összefoglalója a kutatáshoz (V30). Az AI szövege és keresőkifejezése egyszer
// érkezik (DONE / FAILED); az orvos szerkesztése mellé kerül, az eredetit nem írja felül.
@Entity
@Table(name = "research_context")
public class ResearchContext {

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
    private ResearchContextStatus status;

    private OffsetDateTime completedAt;

    private String error;

    private String summary;

    private String query;

    // a modellnek átadott adatok azonosítói típusonként (mint a clinical_summary.input)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private Map<String, List<Long>> input;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> warnings;

    private String modelName;

    private Long seed;

    private String promptHash;

    private String extractorVersion;

    private String editedText;

    private String editedQuery;

    private Long editedBy;

    private OffsetDateTime editedAt;

    protected ResearchContext() {
    }

    public ResearchContext(Long patientId, Long requestedBy, Map<String, List<Long>> input) {
        this.patientId = patientId;
        this.requestedBy = requestedBy;
        this.input = input;
        this.status = ResearchContextStatus.PROCESSING;
    }

    public void complete(String summary, String query, List<String> warnings, String modelName, Long seed,
            String promptHash, String extractorVersion) {
        if (status != ResearchContextStatus.PROCESSING) {
            throw new IllegalStateException("A(z) " + id + " összefoglaló eredménye már megérkezett");
        }
        this.summary = summary;
        this.query = query;
        this.warnings = List.copyOf(warnings);
        this.modelName = modelName;
        this.seed = seed;
        this.promptHash = promptHash;
        this.extractorVersion = extractorVersion;
        this.status = ResearchContextStatus.DONE;
        this.completedAt = OffsetDateTime.now();
    }

    public void fail(String error) {
        if (status != ResearchContextStatus.PROCESSING) {
            return;
        }
        this.error = error;
        this.status = ResearchContextStatus.FAILED;
        this.completedAt = OffsetDateTime.now();
    }

    // Az orvos szerkesztése: a szöveg és a keresőkifejezés együtt (a DB CHECK is így kéri)
    public void edit(String text, String query, Long userId) {
        if (status != ResearchContextStatus.DONE) {
            throw new IllegalStateException("Csak kész összefoglaló szerkeszthető");
        }
        String t = text == null ? "" : text.strip();
        String q = query == null ? "" : query.strip();
        if (t.isEmpty() || q.isEmpty()) {
            throw new IllegalArgumentException("Az összefoglaló és a keresőkifejezés nem lehet üres");
        }
        this.editedText = t;
        this.editedQuery = q;
        this.editedBy = userId;
        this.editedAt = OffsetDateTime.now();
    }

    public String effectiveText() {
        return editedText != null ? editedText : summary;
    }

    public String effectiveQuery() {
        return editedQuery != null ? editedQuery : query;
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

    public ResearchContextStatus getStatus() {
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

    public String getQuery() {
        return query;
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

    public String getEditedText() {
        return editedText;
    }

    public String getEditedQuery() {
        return editedQuery;
    }

    public Long getEditedBy() {
        return editedBy;
    }

    public OffsetDateTime getEditedAt() {
        return editedAt;
    }
}
