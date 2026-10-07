package bme.mediai.lab.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// MERRE: a lelet melyik oldalán, melyik téglalapban áll az eredmény sora (a laphoz normált 0–1)
public record LabSourceBox(
    @NotNull @Min(1) Integer page,
    @NotNull @DecimalMin("0") @DecimalMax("1") Double x,
    @NotNull @DecimalMin("0") @DecimalMax("1") Double y,
    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") Double width,
    @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") Double height
) {
}
