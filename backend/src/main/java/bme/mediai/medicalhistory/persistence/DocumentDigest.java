package bme.mediai.medicalhistory.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Egy (nem labor-) dokumentum kivonata a kórtörténethez (V31). A Python készíti, a kórtörténet
// eredményével együtt érkezik; dokumentumonként egy sor — promptváltáskor a service lecseréli.
// Megváltoztathatatlan: a mezőket csak a konstruktor állítja.
@Entity
@Table(name = "document_digest")
public class DocumentDigest {

    public record Event(String date, String title, String quote) {
    }

    public record Diagnosis(String name, String bnoCode, String since, String quote) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private Long documentId;

    @Column(updatable = false)
    private Long patientId;

    @Column(updatable = false)
    private String title;

    @Column(updatable = false)
    private String place;

    @Column(updatable = false)
    private String summary;

    @Column(updatable = false)
    private String quote;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<Event> events;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<Diagnosis> diagnoses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<String> warnings;

    @Column(updatable = false)
    private String modelName;

    @Column(updatable = false)
    private Long seed;

    @Column(updatable = false)
    private String promptHash;

    @Column(updatable = false)
    private String extractorVersion;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected DocumentDigest() {
    }

    public DocumentDigest(Long documentId, Long patientId, String title, String place, String summary, String quote,
            List<Event> events, List<Diagnosis> diagnoses, List<String> warnings, String modelName, Long seed,
            String promptHash, String extractorVersion) {
        this.documentId = documentId;
        this.patientId = patientId;
        this.title = title;
        this.place = place;
        this.summary = summary;
        this.quote = quote;
        this.events = List.copyOf(events);
        this.diagnoses = List.copyOf(diagnoses);
        this.warnings = List.copyOf(warnings);
        this.modelName = modelName;
        this.seed = seed;
        this.promptHash = promptHash;
        this.extractorVersion = extractorVersion;
    }

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public Long getPatientId() {
        return patientId;
    }

    public String getTitle() {
        return title;
    }

    public String getPlace() {
        return place;
    }

    public String getSummary() {
        return summary;
    }

    public String getQuote() {
        return quote;
    }

    public List<Event> getEvents() {
        return events;
    }

    public List<Diagnosis> getDiagnoses() {
        return diagnoses;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
