package bme.mediai.research.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import bme.mediai.research.persistence.Relevance;

// Python → Spring callback: POST /api/research/{rid}/articles/{aid}/evaluation. FAILED-nél csak az error.
public record ArticleEvaluationResultRequest(

    @NotNull @Pattern(regexp = "^(DONE|FAILED)$", message = "The status must be DONE or FAILED")
    String status,

    @Size(max = 2000)
    String error,

    @Size(max = 2000)
    String summary,

    Relevance relevance,

    @Size(max = 1000)
    String relevanceReason,

    List<@NotBlank @Size(max = 500) String> warnings,

    @Valid
    Model model
) {
    public record Model(
        @NotBlank @Size(max = 128) String modelName,
        @NotNull Long seed,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String promptHash
    ) {
    }
}
