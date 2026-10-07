package bme.mediai.summary.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import bme.mediai.summary.persistence.ClinicalSummary;
import bme.mediai.summary.persistence.SummaryStatus;

public record ClinicalSummaryResponse(
        Long id,
        Long patientId,
        SummaryStatus status,
        Long requestedBy,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt,
        String error,
        LocalDate periodStart,
        LocalDate periodEnd,
        String summary,
        List<String> recommendations,
        // a szövegben olyan szám, ami a bemenetben nem szerepel — a felületnek ki kell írnia
        List<String> warnings,
        // HOGY: melyik modell, seed, prompt
        Model model,
        // hány adatból készült, típusonként
        Map<String, Integer> inputCounts,
        // az összefoglaló óta új dokumentum/állítás érkezett — érdemes frissíteni
        boolean stale
) {
    public record Model(String modelName, Long seed, String promptHash, String extractorVersion) {
    }

    public static ClinicalSummaryResponse from(ClinicalSummary s, boolean stale) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        s.getInput().forEach((k, v) -> counts.put(k.replace("Ids", ""), v.size()));
        return new ClinicalSummaryResponse(
                s.getId(), s.getPatientId(), s.getStatus(), s.getRequestedBy(), s.getRequestedAt(),
                s.getCompletedAt(), s.getError(), s.getPeriodStart(), s.getPeriodEnd(), s.getSummary(),
                s.getRecommendations() == null ? List.of() : s.getRecommendations(),
                s.getWarnings() == null ? List.of() : s.getWarnings(),
                s.getModelName() == null ? null
                        : new Model(s.getModelName(), s.getSeed(), s.getPromptHash(), s.getExtractorVersion()),
                counts, stale);
    }
}
