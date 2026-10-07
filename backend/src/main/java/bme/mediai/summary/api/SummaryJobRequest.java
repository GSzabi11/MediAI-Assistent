package bme.mediai.summary.api;

// Spring → Python: POST {ai-service}/summaries (docs/clinical-summary.md)
public record SummaryJobRequest(Long summaryId, String callbackUrl, SummaryFacts facts) {
}
