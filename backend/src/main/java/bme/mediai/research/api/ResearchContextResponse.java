package bme.mediai.research.api;

import java.time.OffsetDateTime;
import java.util.List;

import bme.mediai.research.persistence.ResearchContext;
import bme.mediai.research.persistence.ResearchContextStatus;

public record ResearchContextResponse(
        Long id,
        Long patientId,
        ResearchContextStatus status,
        Long requestedBy,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt,
        String error,
        // az AI eredeti szövege és keresőkifejezése
        String summary,
        String query,
        // a megjelenítendő / a kutatás alapja: az orvos szerkesztése, ha van
        String effectiveText,
        String effectiveQuery,
        boolean edited,
        Long editedBy,
        OffsetDateTime editedAt,
        List<String> warnings,
        Model model
) {
    public record Model(String modelName, Long seed, String promptHash, String extractorVersion) {
    }

    public static ResearchContextResponse from(ResearchContext c) {
        return new ResearchContextResponse(c.getId(), c.getPatientId(), c.getStatus(), c.getRequestedBy(),
                c.getRequestedAt(), c.getCompletedAt(), c.getError(), c.getSummary(), c.getQuery(),
                c.effectiveText(), c.effectiveQuery(), c.getEditedText() != null, c.getEditedBy(), c.getEditedAt(),
                c.getWarnings() == null ? List.of() : c.getWarnings(),
                c.getModelName() == null ? null
                        : new Model(c.getModelName(), c.getSeed(), c.getPromptHash(), c.getExtractorVersion()));
    }
}
