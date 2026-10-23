package bme.mediai.research.api;

import java.time.OffsetDateTime;
import java.util.List;

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
        Counts articles
) {
    // pending: értékelésre váró cikkek (a felület addig frissít)
    public record Counts(long total, long excluded, long bookmarked, long evaluated, long pending) {
        public static final Counts EMPTY = new Counts(0, 0, 0, 0, 0);
    }

    public static ResearchResponse from(Research r, Counts counts) {
        return new ResearchResponse(r.getId(), r.getPatientId(), r.getContextId(), r.getRequestedBy(),
                r.getCreatedAt(), r.getStatus(), r.getProgress(), r.getCompletedAt(), r.getError(),
                r.getContextText(), r.getExtraPrompt(), r.getQuery(), r.getSources(), r.getMaxResults(),
                r.getTotalFound(), counts);
    }
}
