package bme.mediai.recordingfinding.api;

import java.util.List;

// GET /api/recordings/{id}/findings: a legutóbbi futás (null, ha még nem volt; lehet PROCESSING / FAILED is)
// és az érvényes elemek — a legfrissebb KÉSZ futás AI-elemei + az orvos elemei, a törölt nélkül
public record FindingsResponse(
        ExtractionResponse extraction,
        Long itemsFromExtractionId,
        List<FindingResponse> items
) {
}
