package bme.mediai.epicrisis.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import bme.mediai.medicalhistory.persistence.MedicalHistory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Epikrízis egy ellátáshoz (V33). Külön tábla, nem statement (mint a medical_history). PROCESSING-ként jön
// létre a kéréskor rögzített bemenettel (a kiválasztott leletek, felvételek) és gyógyszer-pillanatképpel; az
// AI-eredményt egyszer kapja meg. Az orvos szerkesztése (editedHtml) mellé kerül — az AI eredetije nem változik.
@Entity
@Table(name = "epicrisis")
public class Epicrisis {

    // forrás: a lelet (documentId) VAGY a felvétel megszólalásai (recordingId + turnIds); bnoCode csak a leletből
    public record Diagnosis(String name, String bnoCode, Long documentId, Long recordingId, List<Integer> turnIds) {
    }

    // flag: ha nem null, ellenőrizendő (nincs szó szerinti idézet, szokatlan javaslat, ellenőrizetlen szám)
    public record Finding(String text, Long documentId, Long recordingId, List<Integer> turnIds, String quote,
            String flag) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(updatable = false)
    private Long patientId;

    @Column(updatable = false)
    private Long medicalCareId;

    @Column(updatable = false)
    private Long requestedBy;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime requestedAt;

    @Enumerated(EnumType.STRING)
    private EpicrisisStatus status;

    private OffsetDateTime completedAt;

    private String error;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Diagnosis> diagnoses;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Finding> findings;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> recommendations;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> warnings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<MedicalHistory.Medication> medications;

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

    protected Epicrisis() {
    }

    public Epicrisis(Long patientId, Long medicalCareId, Long requestedBy, List<MedicalHistory.Medication> medications,
            Map<String, List<Long>> input) {
        this.patientId = patientId;
        this.medicalCareId = medicalCareId;
        this.requestedBy = requestedBy;
        this.status = EpicrisisStatus.PROCESSING;
        this.medications = List.copyOf(medications);
        this.input = input;
    }

    public void complete(List<Diagnosis> diagnoses, List<Finding> findings, List<String> recommendations,
            List<String> warnings, String modelName, Long seed, String promptHash, String extractorVersion) {
        if (status != EpicrisisStatus.PROCESSING) {
            throw new IllegalStateException("A(z) " + id + " epikrízis eredménye már megérkezett");
        }
        this.diagnoses = List.copyOf(diagnoses);
        this.findings = List.copyOf(findings);
        this.recommendations = List.copyOf(recommendations);
        this.warnings = List.copyOf(warnings);
        this.modelName = modelName;
        this.seed = seed;
        this.promptHash = promptHash;
        this.extractorVersion = extractorVersion;
        this.status = EpicrisisStatus.DONE;
        this.completedAt = OffsetDateTime.now();
    }

    public void fail(String error) {
        if (status != EpicrisisStatus.PROCESSING) {
            return;
        }
        this.error = error;
        this.status = EpicrisisStatus.FAILED;
        this.completedAt = OffsetDateTime.now();
    }

    // a html-t a hívó már szűrte; újraszerkesztés felülírja az előzőt
    public void edit(String html, Long userId) {
        if (status != EpicrisisStatus.DONE) {
            throw new IllegalStateException("Csak kész epikrízis szerkeszthető");
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

    public Long getMedicalCareId() {
        return medicalCareId;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public OffsetDateTime getRequestedAt() {
        return requestedAt;
    }

    public EpicrisisStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public String getError() {
        return error;
    }

    public List<Diagnosis> getDiagnoses() {
        return diagnoses == null ? List.of() : diagnoses;
    }

    public List<Finding> getFindings() {
        return findings == null ? List.of() : findings;
    }

    public List<String> getRecommendations() {
        return recommendations == null ? List.of() : recommendations;
    }

    public List<String> getWarnings() {
        return warnings == null ? List.of() : warnings;
    }

    public List<MedicalHistory.Medication> getMedications() {
        return medications == null ? List.of() : medications;
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
