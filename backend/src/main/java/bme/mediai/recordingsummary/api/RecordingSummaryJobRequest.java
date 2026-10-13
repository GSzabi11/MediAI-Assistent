package bme.mediai.recordingsummary.api;

import java.util.List;

// Spring → Python: POST /recording-summaries. A Python nem olvas adatbázist: a teljes átirat
// (a megjelenített, esetleg AI-javított szöveggel) és a kulcsszavak a kérésben mennek.
public record RecordingSummaryJobRequest(
        Long summaryId,
        String mode,
        List<Turn> utterances,
        List<String> keywords,
        String callbackUrl
) {
    public record Turn(Integer turnId, String speaker, String text) {
    }
}
