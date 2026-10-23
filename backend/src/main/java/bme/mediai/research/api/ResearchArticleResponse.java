package bme.mediai.research.api;

import java.time.OffsetDateTime;
import java.util.List;

import bme.mediai.research.persistence.ArticleEvalStatus;
import bme.mediai.research.persistence.Relevance;
import bme.mediai.research.persistence.ResearchArticle;
import bme.mediai.research.persistence.ResearchSource;

public record ResearchArticleResponse(
        Long id,
        Long researchId,
        int rank,
        String pmid,
        String doi,
        String pubmedUrl,
        String doiUrl,
        String title,
        List<String> authors,
        String journal,
        Integer pubYear,
        String abstractText,
        List<ResearchSource> sources,
        List<String> publicationTypes,
        // OpenAlex; null = nem ismert (nem 0)
        Integer citationCount,
        Evaluation evaluation,
        boolean excluded,
        boolean bookmarked
) {
    // a modell kimenete (MODEL_INFERENCE jellegű): relevancia-kategória + indoklás, a modell adataival
    public record Evaluation(ArticleEvalStatus status, String summary, Relevance relevance, String relevanceReason,
            String error, List<String> warnings, String modelName, Long seed, String promptHash,
            OffsetDateTime evaluatedAt) {
    }

    public static ResearchArticleResponse from(ResearchArticle a) {
        Evaluation evaluation = new Evaluation(a.getEvalStatus(), a.getSummary(), a.getRelevance(),
                a.getRelevanceReason(), a.getEvalError(), a.getEvalWarnings() == null ? List.of() : a.getEvalWarnings(),
                a.getEvalModel(), a.getEvalSeed(), a.getEvalPromptHash(), a.getEvaluatedAt());
        return new ResearchArticleResponse(a.getId(), a.getResearchId(), a.getRank(), a.getPmid(), a.getDoi(),
                "https://pubmed.ncbi.nlm.nih.gov/" + a.getPmid() + "/",
                a.getDoi() == null ? null : "https://doi.org/" + a.getDoi(),
                a.getTitle(), a.getAuthors(), a.getJournal(), a.getPubYear(), a.getAbstractText(), a.getSources(),
                a.getPublicationTypes() == null ? List.of() : a.getPublicationTypes(), a.getCitationCount(),
                evaluation, a.isExcluded(), a.isBookmarked());
    }
}
