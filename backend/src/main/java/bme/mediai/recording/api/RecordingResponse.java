package bme.mediai.recording.api;

import java.time.OffsetDateTime;
import java.util.List;

import bme.mediai.recording.persistence.Recording;
import bme.mediai.recording.persistence.RecordingMode;
import bme.mediai.recording.persistence.RecordingStatus;

public record RecordingResponse(
        Long id,
        Long patientId,
        Long userId,
        Long medicalCareId,
        Long voiceProfileId,
        RecordingMode mode,
        RecordingStatus status,
        String audioPath,
        Integer durationSec,
        String asrModel,
        OffsetDateTime recordedAt,
        String processingError,
        List<String> processingWarnings,
        OffsetDateTime processedAt,
        boolean audioDeleted,
        String reviewModel
) {
    public static RecordingResponse from(Recording recording) {
        return new RecordingResponse(
                recording.getId(), recording.getPatientId(), recording.getUserId(),
                recording.getMedicalCareId(), recording.getVoiceProfileId(), recording.getMode(),
                recording.getStatus(), recording.getAudioPath(), recording.getDurationSec(),
                recording.getAsrModel(), recording.getRecordedAt(), recording.getProcessingError(),
                recording.getProcessingWarnings(), recording.getProcessedAt(), recording.getAudioDeletedAt() != null,
                recording.getReviewModel());
    }
}
