package bme.mediai.research.api;

import java.util.List;

// Spring → Python: POST {ai-service}/research-rankings. Az összes cikk (a kizártak nélkül) egy feladatban;
// a kontextus a helyi modellhez megy, nem a hálózatra.
public record ResearchRankingJobRequest(Long researchId, String callbackUrl, String progressUrl, String contextText,
        String extraPrompt, List<Article> articles) {

    public record Article(Long id, String title, String abstractText, String journal, Integer pubYear,
            List<String> publicationTypes) {
    }
}
