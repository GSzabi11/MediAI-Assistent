package bme.mediai.recordingsummary.api;

import java.time.OffsetDateTime;
import java.util.List;

import bme.mediai.recordingsummary.persistence.RecordingSummary;
import bme.mediai.recordingsummary.persistence.RecordingSummaryStatus;

public record RecordingSummaryResponse(
        Long id,
        Long recordingId,
        RecordingSummaryStatus status,
        Long requestedBy,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt,
        String error,
        String summary,
        List<RecordingSummary.Point> points,
        List<String> keywords,
        List<String> warnings,
        String modelName,
        Long seed,
        String promptHash,
        String editedText,
        Long editedBy,
        OffsetDateTime editedAt
) {
    public static RecordingSummaryResponse from(RecordingSummary s) {
        return new RecordingSummaryResponse(s.getId(), s.getRecordingId(), s.getStatus(), s.getRequestedBy(),
                s.getRequestedAt(), s.getCompletedAt(), s.getError(), s.getSummary(), s.getPoints(), s.getKeywords(),
                s.getWarnings(), s.getModelName(), s.getSeed(), s.getPromptHash(), s.getEditedText(), s.getEditedBy(),
                s.getEditedAt());
    }
}
