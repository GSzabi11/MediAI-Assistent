package bme.mediai.summary.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Python → Spring callback: POST /api/clinical-summaries/{id}/result. FAILED-nél csak az error.
public record SummaryResultRequest(

    @NotNull @Pattern(regexp = "^(DONE|FAILED)$", message = "The status must be DONE or FAILED")
    String status,

    @Size(max = 2000)
    String error,

    @Size(max = 4000)
    String summary,

    @Size(max = 8)
    List<@NotBlank @Size(max = 500) String> recommendations,

    List<@NotBlank @Size(max = 500) String> warnings,

    @Valid
    Model model
) {
    public record Model(
        @NotBlank @Size(max = 128) String modelName,
        @NotNull Long seed,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String promptHash,
        @NotBlank @Size(max = 64) String extractorVersion
    ) {
    }
}
