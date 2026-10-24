package bme.mediai.recordingsummary.api;

import java.util.List;

import bme.mediai.recordingfinding.api.FindingFact;

// Spring → Python: POST /recording-summaries. A Python nem olvas adatbázist: a teljes átirat
// (a megjelenített, esetleg AI-javított szöveggel), a kulcsszavak és a csoportok érvényes elemei (az orvos
// szerkesztett állapotában — mindegyiknek szerepelnie kell az összefoglalóban) a kérésben mennek.
public record RecordingSummaryJobRequest(
        Long summaryId,
        String mode,
        List<Turn> utterances,
        List<String> keywords,
        List<FindingFact> findings,
        String callbackUrl
) {
    public record Turn(Integer turnId, String speaker, String text) {
    }
}
