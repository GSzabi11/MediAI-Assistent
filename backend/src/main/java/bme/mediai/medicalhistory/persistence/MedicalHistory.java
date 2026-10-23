package bme.mediai.medicalhistory.persistence;

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

// Kórtörténeti összefoglaló (V31). Külön tábla, nem statement (a felhasználó döntése). PROCESSING-ként
// jön létre a kéréskor rögzített bemenettel és gyógyszer-pillanatképpel; az AI-eredményt egyszer kapja
// meg (DONE / FAILED). Az orvos szerkesztése (editedHtml) mellé kerül — az AI eredetije nem változik.
@Entity
@Table(name = "medical_history")
public class MedicalHistory {

    // kind: LAB (laborlelet, determinisztikus), DOCUMENT (a lelet kivonata), HISTORY (korábbi esemény
    // az anamnézisből). flag: ha nem null, ellenőrizendő (pl. nem támasztható alá idézettel)
    public record TimelineItem(String date, String title, String text, String place, Long documentId, String kind,
            String flag) {
    }

    // date: év vagy ÉÉÉÉ-HH-NN; bnoCode csak a leletből (szó szerint), különben null
    public record Diagnosis(String name, String bnoCode, String date, Long documentId) {
    }

    public record Medication(Long id, String name, String dose, String route, boolean verified) {
    }

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
    private MedicalHistoryStatus status;

    private OffsetDateTime completedAt;

    private String error;

    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<TimelineItem> timeline;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Diagnosis> diagnoses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<Medication> medications;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> recommendations;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> warnings;

    // a modellnek átadott adatok azonosítói típusonként
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private Map<String, List<Long>> input;

    private String modelName;

    private Long seed;

    private String promptHash;

    private String extractorVersion;

    private String editedHtml;

    private Long editedBy;

    private OffsetDateTime editedAt;

    protected MedicalHistory() {
    }

    public MedicalHistory(Long patientId, Long requestedBy, List<Medication> medications,
            Map<String, List<Long>> input) {
        this.patientId = patientId;
        this.requestedBy = requestedBy;
        this.status = MedicalHistoryStatus.PROCESSING;
        this.medications = List.copyOf(medications);
        this.input = input;
    }

    public void complete(String summary, List<TimelineItem> timeline, List<Diagnosis> diagnoses,
            List<String> recommendations, List<String> warnings, String modelName, Long seed, String promptHash,
            String extractorVersion) {
        if (status != MedicalHistoryStatus.PROCESSING) {
            throw new IllegalStateException("A(z) " + id + " kórtörténet eredménye már megérkezett");
        }
        this.summary = summary;
        this.timeline = List.copyOf(timeline);
        this.diagnoses = List.copyOf(diagnoses);
        this.recommendations = List.copyOf(recommendations);
        this.warnings = List.copyOf(warnings);
        this.modelName = modelName;
        this.seed = seed;
        this.promptHash = promptHash;
        this.extractorVersion = extractorVersion;
        this.status = MedicalHistoryStatus.DONE;
        this.completedAt = OffsetDateTime.now();
    }

    public void fail(String error) {
        if (status != MedicalHistoryStatus.PROCESSING) {
            return;
        }
        this.error = error;
        this.status = MedicalHistoryStatus.FAILED;
        this.completedAt = OffsetDateTime.now();
    }

    // a html-t a hívó már szűrte (MedicalHistoryHtml.sanitize); újraszerkesztés felülírja az előzőt
    public void edit(String html, Long userId) {
        if (status != MedicalHistoryStatus.DONE) {
            throw new IllegalStateException("Csak kész kórtörténet szerkeszthető");
        }
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("A szerkesztett szöveg nem lehet üres");
        }
        this.editedHtml = html;
        this.editedBy = userId;
        this.editedAt = OffsetDateTime.now();
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

    public MedicalHistoryStatus getStatus() {
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

    public List<TimelineItem> getTimeline() {
        return timeline == null ? List.of() : timeline;
    }

    public List<Diagnosis> getDiagnoses() {
        return diagnoses == null ? List.of() : diagnoses;
    }

    public List<Medication> getMedications() {
        return medications == null ? List.of() : medications;
    }

    public List<String> getRecommendations() {
        return recommendations == null ? List.of() : recommendations;
    }

    public List<String> getWarnings() {
        return warnings == null ? List.of() : warnings;
    }

    public Map<String, List<Long>> getInput() {
        return input;
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

    public String getEditedHtml() {
        return editedHtml;
    }

    public Long getEditedBy() {
        return editedBy;
    }

    public OffsetDateTime getEditedAt() {
        return editedAt;
    }
}
