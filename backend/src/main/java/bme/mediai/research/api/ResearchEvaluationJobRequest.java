package bme.mediai.research.api;

import java.util.List;

// Spring → Python: POST {ai-service}/research-evaluations. A kontextus a helyi modellhez megy, nem a hálózatra.
public record ResearchEvaluationJobRequest(Long researchId, String contextText, String extraPrompt,
        List<Article> articles) {

    public record Article(Long id, String callbackUrl, String title, String abstractText, String journal,
            Integer pubYear, List<String> publicationTypes) {
    }
}
