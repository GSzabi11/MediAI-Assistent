package bme.mediai.recording.api;

import java.time.OffsetDateTime;

import bme.mediai.recording.persistence.Recording;
import bme.mediai.recording.persistence.RecordingMode;
import bme.mediai.recording.persistence.RecordingStatus;

public record RecordingResponse(
        Long id,
        Long patientId,
        Long userId,
        Long encounterId,
        Long voiceProfileId,
        RecordingMode mode,
        RecordingStatus status,
        String audioPath,
        Integer durationSec,
        String asrModel,
        OffsetDateTime recordedAt
) {
    public static RecordingResponse from(Recording recording) {
        return new RecordingResponse(
                recording.getId(), recording.getPatientId(), recording.getUserId(),
                recording.getEncounterId(), recording.getVoiceProfileId(), recording.getMode(),
                recording.getStatus(), recording.getAudioPath(), recording.getDurationSec(),
                recording.getAsrModel(), recording.getRecordedAt());
    }
}
