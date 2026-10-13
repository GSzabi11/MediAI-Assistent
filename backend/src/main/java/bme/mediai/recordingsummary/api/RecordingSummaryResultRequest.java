package bme.mediai.recordingsummary.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// Python → Spring: POST /api/recording-summaries/{id}/result. FAILED-nél csak az error.
public record RecordingSummaryResultRequest(

    @NotNull @Pattern(regexp = "^(DONE|FAILED)$", message = "The status must be DONE or FAILED")
    String status,

    @Size(max = 2000)
    String error,

    @Size(max = 6000)
    String summary,

    @Size(max = 30)
    List<@Valid Point> points,

    @Size(max = 30)
    List<@NotBlank @Size(max = 500) String> warnings,

    @Valid
    Model model
) {
    public record Point(
        @NotBlank @Size(max = 1000) String text,
        @NotNull @Size(max = 20) List<@NotNull @PositiveOrZero Integer> turnIds
    ) {
    }

    public record Model(
        @NotBlank @Size(max = 128) String modelName,
        @NotNull Long seed,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String promptHash
    ) {
    }
}
