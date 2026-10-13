package bme.mediai.recording.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import bme.mediai.recording.exception.InvalidStatusTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "recording")
public class Recording {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long patientId;

    private Long userId;

    private Long medicalCareId;

    private Long voiceProfileId;

    @Enumerated(EnumType.STRING)
    private RecordingMode mode;

    @Enumerated(EnumType.STRING)
    private RecordingStatus status;

    private String audioPath;

    private Integer durationSec;

    private String asrModel;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime recordedAt;

    private String processingError;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> processingWarnings = List.of();

    private OffsetDateTime processedAt;

    private OffsetDateTime audioDeletedAt;

    private String reviewModel;

    protected Recording() {
    }

    public Recording(Long patientId, Long userId, Long medicalCareId, Long voiceProfileId,
            RecordingMode mode, String audioPath, String asrModel) {
        this.patientId = patientId;
        this.userId = userId;
        this.medicalCareId = medicalCareId;
        this.voiceProfileId = voiceProfileId;
        this.mode = mode;
        this.audioPath = audioPath;
        this.asrModel = asrModel;
        this.status = RecordingStatus.RECORDING;
    }

    public void changeStatus(RecordingStatus next, Integer durationSec) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStatusTransitionException(id, status, next);
        }
        if (next == RecordingStatus.PROCESSING) {
            if (durationSec == null) {
                throw new IllegalArgumentException("A PROCESSING állapothoz meg kell adni a durationSec-et");
            }
            this.durationSec = durationSec;
        }
        this.status = next;
    }

    // A Python hangszolgáltatás eredménye (V27). Az ASR-modellt a Python adja meg (azt használta),
    // a felvételkor beírt érték csak a beállított alapérték volt.
    public void completeProcessing(String asrModel, String reviewModel, List<String> warnings) {
        changeStatus(RecordingStatus.DONE, null);
        this.asrModel = asrModel;
        this.reviewModel = reviewModel;
        this.processingWarnings = List.copyOf(warnings);
        this.processedAt = OffsetDateTime.now();
    }

    public void failProcessing(String error) {
        if (!status.canTransitionTo(RecordingStatus.FAILED)) {
            return;
        }
        this.processingError = error;
        this.processedAt = OffsetDateTime.now();
        changeStatus(RecordingStatus.FAILED, null);
    }

    public void markAudioDeleted() {
        if (audioDeletedAt == null) {
            this.audioDeletedAt = OffsetDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getMedicalCareId() {
        return medicalCareId;
    }

    public Long getVoiceProfileId() {
        return voiceProfileId;
    }

    public RecordingMode getMode() {
        return mode;
    }

    public RecordingStatus getStatus() {
        return status;
    }

    public String getAudioPath() {
        return audioPath;
    }

    public Integer getDurationSec() {
        return durationSec;
    }

    public String getAsrModel() {
        return asrModel;
    }

    public OffsetDateTime getRecordedAt() {
        return recordedAt;
    }

    public String getProcessingError() {
        return processingError;
    }

    public List<String> getProcessingWarnings() {
        return processingWarnings == null ? List.of() : processingWarnings;
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }

    public OffsetDateTime getAudioDeletedAt() {
        return audioDeletedAt;
    }

    public String getReviewModel() {
        return reviewModel;
    }

}
