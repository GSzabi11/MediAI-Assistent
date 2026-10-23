package bme.mediai.recordingfinding.api;

import java.time.OffsetDateTime;
import java.util.List;

import bme.mediai.recordingfinding.persistence.RecordingExtraction;
import bme.mediai.recordingfinding.persistence.RecordingExtractionStatus;

public record ExtractionResponse(
        Long id,
        Long recordingId,
        RecordingExtractionStatus status,
        Long requestedBy,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt,
        String error,
        List<String> warnings,
        String modelName,
        Long seed,
        String promptHash
) {
    public static ExtractionResponse from(RecordingExtraction e) {
        return new ExtractionResponse(e.getId(), e.getRecordingId(), e.getStatus(), e.getRequestedBy(),
                e.getRequestedAt(), e.getCompletedAt(), e.getError(), e.getWarnings(), e.getModelName(), e.getSeed(),
                e.getPromptHash());
    }
}
