package bme.mediai.research.api;

import java.time.OffsetDateTime;
import java.util.List;

import bme.mediai.research.persistence.RankingStatus;
import bme.mediai.research.persistence.Research;
import bme.mediai.research.persistence.ResearchSource;
import bme.mediai.research.persistence.ResearchStatus;

public record ResearchResponse(
        Long id,
        Long patientId,
        Long contextId,
        Long requestedBy,
        OffsetDateTime createdAt,
        ResearchStatus status,
        // keresés közben: mit csinál éppen (a Python állapotüzenete)
        String progress,
        OffsetDateTime completedAt,
        String error,
        String contextText,
        String extraPrompt,
        String query,
        List<ResearchSource> sources,
        int maxResults,
        Integer totalFound,
        Counts articles,
        Ranking ranking
) {
    // az AI-rangsor (V32): állapot + a modell adatai; a cikkek helye a ResearchArticleResponse.ranking-ben
    public record Ranking(RankingStatus status, String progress, String error, List<String> warnings,
            String modelName, Long seed, String promptHash, OffsetDateTime rankedAt) {
    }

    // pending: értékelésre váró cikkek (a felület addig frissít)
    public record Counts(long total, long excluded, long bookmarked, long evaluated, long pending) {
        public static final Counts EMPTY = new Counts(0, 0, 0, 0, 0);
    }

    public static ResearchResponse from(Research r, Counts counts) {
        return new ResearchResponse(r.getId(), r.getPatientId(), r.getContextId(), r.getRequestedBy(),
                r.getCreatedAt(), r.getStatus(), r.getProgress(), r.getCompletedAt(), r.getError(),
                r.getContextText(), r.getExtraPrompt(), r.getQuery(), r.getSources(), r.getMaxResults(),
                r.getTotalFound(), counts, new Ranking(r.getRankingStatus(), r.getRankingProgress(),
                        r.getRankingError(), r.getRankingWarnings() == null ? List.of() : r.getRankingWarnings(),
                        r.getRankingModel(), r.getRankingSeed(), r.getRankingPromptHash(), r.getRankedAt()));
    }
}
