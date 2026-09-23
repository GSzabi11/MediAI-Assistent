package bme.mediai.recording.persistence;

import java.time.OffsetDateTime;

import org.hibernate.annotations.Generated;

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

    private Long encounterId;

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

    protected Recording() {
    }

    public Recording(Long patientId, Long userId, Long encounterId, Long voiceProfileId,
            RecordingMode mode, String audioPath, String asrModel) {
        this.patientId = patientId;
        this.userId = userId;
        this.encounterId = encounterId;
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

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getEncounterId() {
        return encounterId;
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

}
