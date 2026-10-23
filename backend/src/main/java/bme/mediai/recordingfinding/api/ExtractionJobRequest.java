package bme.mediai.recordingfinding.api;

import java.util.List;

// Spring → Python: POST /recording-findings. A Python nem olvas adatbázist: a teljes átirat (a
// megjelenített, esetleg AI-javított szöveggel) a kérésben megy.
public record ExtractionJobRequest(
        Long extractionId,
        String mode,
        List<Turn> utterances,
        String callbackUrl
) {
    public record Turn(Integer turnId, String speaker, String text) {
    }
}
