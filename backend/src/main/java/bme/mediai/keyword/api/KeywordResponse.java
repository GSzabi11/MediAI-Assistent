package bme.mediai.keyword.api;

import java.time.OffsetDateTime;

import bme.mediai.keyword.persistence.KeywordOrigin;
import bme.mediai.keyword.persistence.KeywordStatus;
import bme.mediai.keyword.persistence.RecordingKeyword;

public record KeywordResponse(
        Long id,
        Long recordingId,
        String term,
        KeywordOrigin origin,
        KeywordStatus status,
        String modelName,
        Long userId,
        OffsetDateTime createdAt,
        OffsetDateTime decidedAt
) {
    public static KeywordResponse from(RecordingKeyword k) {
        return new KeywordResponse(k.getId(), k.getRecordingId(), k.getTerm(), k.getOrigin(), k.getStatus(),
                k.getModelName(), k.getUserId(), k.getCreatedAt(), k.getDecidedAt());
    }
}
