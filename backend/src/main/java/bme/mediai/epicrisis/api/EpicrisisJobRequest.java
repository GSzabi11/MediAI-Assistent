package bme.mediai.epicrisis.api;

// Spring → Python: POST {ai-service}/epicrises (docs/epicrisis.md). progressUrl: a lépésenkénti haladás.
public record EpicrisisJobRequest(Long epicrisisId, String callbackUrl, String progressUrl, EpicrisisFacts facts) {
}
