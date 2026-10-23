package bme.mediai.recordingfinding.api;

import java.util.List;

import bme.mediai.recordingfinding.persistence.FindingCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// Python → Spring: POST /api/recording-extractions/{id}/result. FAILED-nél csak az error.
public record ExtractionResultRequest(

    @NotNull @Pattern(regexp = "^(DONE|FAILED)$", message = "The status must be DONE or FAILED")
    String status,

    @Size(max = 2000)
    String error,

    @Size(max = 100)
    List<@Valid Item> items,

    @Size(max = 50)
    List<@NotBlank @Size(max = 500) String> warnings,

    @Valid
    Model model
) {
    public record Item(
        @NotNull FindingCategory category,
        @NotBlank @Size(max = 500) String text,
        @Size(max = 10) String bnoCode,
        @NotNull @Size(min = 1, max = 20) List<@NotNull @PositiveOrZero Integer> turnIds,
        @Size(max = 500) String flag
    ) {
    }

    public record Model(
        @NotBlank @Size(max = 128) String modelName,
        @NotNull Long seed,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String promptHash
    ) {
    }
}
