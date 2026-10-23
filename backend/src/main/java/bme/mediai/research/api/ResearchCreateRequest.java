package bme.mediai.research.api;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import bme.mediai.research.persistence.ResearchSource;

// Új keresés. A contextText és a query az orvos által látott, jóváhagyott változat (a felület a
// kontextus effectiveText/effectiveQuery-jéből tölti ki, az orvos átírhatja).
public record ResearchCreateRequest(

    @NotNull(message = "The patientId must not be null")
    Long patientId,

    Long userId,

    // melyik összefoglalóból indult (nullázható: az orvos saját szöveggel is kereshet)
    Long contextId,

    @NotBlank(message = "The contextText must not be blank")
    @Size(max = 8000)
    String contextText,

    // az orvos kiegészítő kérése a modellnek (szempont, kérdés)
    @Size(max = 2000)
    String extraPrompt,

    // EZ megy ki a hálózatra (PubMed) — a ResearchQueryGuard ellenőrzi
    @NotBlank(message = "The query must not be blank")
    @Size(max = 1000)
    String query,

    @NotEmpty(message = "At least one source is required")
    List<@NotNull ResearchSource> sources,

    @Min(1) @Max(200)
    Integer maxResults
) {
}
