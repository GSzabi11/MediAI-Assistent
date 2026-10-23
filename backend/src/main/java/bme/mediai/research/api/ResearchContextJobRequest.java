package bme.mediai.research.api;

import bme.mediai.summary.api.SummaryFacts;

// Spring → Python: POST {ai-service}/research-contexts. A tények ugyanazok, mint a Klinikai
// státuszé (név, TAJ, lakcím nélkül) — és csak a helyi modellhez mennek, a hálózatra nem.
public record ResearchContextJobRequest(Long contextId, String callbackUrl, SummaryFacts facts) {
}
