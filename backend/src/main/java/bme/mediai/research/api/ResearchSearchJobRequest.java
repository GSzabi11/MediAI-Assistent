package bme.mediai.research.api;

import java.util.List;

import bme.mediai.research.persistence.ResearchSource;

// Spring → Python: POST {ai-service}/research-searches. Betegadat NINCS benne: csak a (jóváhagyott,
// ellenőrzött) keresőkifejezés megy a külső forrásokhoz.
public record ResearchSearchJobRequest(Long researchId, String callbackUrl, String progressUrl, String query,
        List<ResearchSource> sources, int maxResults) {
}
