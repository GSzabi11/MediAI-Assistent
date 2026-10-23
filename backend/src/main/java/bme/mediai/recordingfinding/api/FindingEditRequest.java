package bme.mediai.recordingfinding.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Az orvos szerkesztése: a szöveg és (panasznál, diagnózisnál) a BNO-kód; üres kód = nincs kód
public record FindingEditRequest(

    @NotNull(message = "The userId must not be null")
    Long userId,

    @NotBlank(message = "The text must not be blank")
    @Size(max = 500)
    String text,

    @Size(max = 10)
    String bnoCode
) {
}
