package bme.mediai.research.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import bme.mediai.research.persistence.Relevance;

// Python → Spring callback: POST /api/research/{id}/ranking/result. FAILED-nél csak az error.
// picks: a legrelevánsabb(ak), a legjobb elöl — csak HIGH előszűrésű cikk lehet.
public record ResearchRankingResultRequest(

    @NotNull @Pattern(regexp = "^(DONE|FAILED)$", message = "The status must be DONE or FAILED")
    String status,

    @Size(max = 2000)
    String error,

    List<@Valid @NotNull Screened> screened,

    @Size(max = 3)
    List<@Valid @NotNull Pick> picks,

    List<@NotBlank @Size(max = 500) String> warnings,

    @Valid
    ArticleEvaluationResultRequest.Model model
) {
    public record Screened(@NotNull Long articleId, @NotNull Relevance relevance) {
    }

    public record Pick(@NotNull Long articleId, @NotBlank @Size(max = 1000) String reason) {
    }
}
