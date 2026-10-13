package bme.mediai.recordingsummary.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Az orvos szerkesztett összefoglalója (a userId a kérésből jön — még nincs bejelentkezés)
public record RecordingSummaryEditRequest(

    @NotNull(message = "The userId must not be null")
    Long userId,

    @NotBlank(message = "The text must not be blank")
    @Size(max = 8000)
    String text
) {
}
