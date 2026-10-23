package bme.mediai.medicalhistory.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Az orvos szerkesztett szövege (rich text, HTML). A Spring szűri: csak a szerkesztő elemei maradnak.
public record MedicalHistoryEditRequest(

    @NotNull(message = "The userId must not be null")
    Long userId,

    @NotBlank(message = "The html must not be blank")
    @Size(max = 200_000)
    String html
) {
}
