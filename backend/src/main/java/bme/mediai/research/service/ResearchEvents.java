package bme.mediai.research.service;

import bme.mediai.research.api.ResearchEvaluationJobRequest;
import bme.mediai.research.api.ResearchRankingJobRequest;
import bme.mediai.research.api.ResearchSearchJobRequest;
import bme.mediai.summary.api.SummaryFacts;

// Commit UTÁN mennek a Pythonnak (a sor csak akkor létezik, ha a tranzakció sikeres)
public final class ResearchEvents {

    private ResearchEvents() {
    }

    public record ContextRequested(Long contextId, SummaryFacts facts) {
    }

    public record SearchRequested(ResearchSearchJobRequest job) {
    }

    public record EvaluationRequested(ResearchEvaluationJobRequest job) {
    }

    public record RankingRequested(ResearchRankingJobRequest job) {
    }
}
