package bme.mediai.recordingfinding.api;

import java.time.OffsetDateTime;
import java.util.List;

import bme.mediai.recordingfinding.persistence.FindingCategory;
import bme.mediai.recordingfinding.persistence.FindingOrigin;
import bme.mediai.recordingfinding.persistence.RecordingFinding;

// text / bnoCode: a megjelenítendő (az orvos szerkesztése, ha van); originalText / originalBnoCode: az eredeti
public record FindingResponse(
        Long id,
        Long recordingId,
        Long extractionId,
        FindingCategory category,
        FindingOrigin origin,
        String text,
        String bnoCode,
        String originalText,
        String originalBnoCode,
        boolean edited,
        List<Integer> turnIds,
        String flag,
        Long createdBy,
        OffsetDateTime createdAt,
        Long editedBy,
        OffsetDateTime editedAt
) {
    public static FindingResponse from(RecordingFinding f) {
        return new FindingResponse(f.getId(), f.getRecordingId(), f.getExtractionId(), f.getCategory(), f.getOrigin(),
                f.getEffectiveText(), f.getEffectiveBnoCode(), f.getText(), f.getBnoCode(), f.isEdited(),
                f.getTurnIds(), f.getFlag(), f.getCreatedBy(), f.getCreatedAt(), f.getEditedBy(), f.getEditedAt());
    }
}
