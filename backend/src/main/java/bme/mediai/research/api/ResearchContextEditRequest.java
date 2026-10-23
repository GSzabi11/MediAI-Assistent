package bme.mediai.research.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Az orvos szerkesztett összefoglalója és keresőkifejezése (a userId a kérésből jön — még nincs bejelentkezés)
public record ResearchContextEditRequest(

    @NotNull(message = "The userId must not be null")
    Long userId,

    @NotBlank(message = "The text must not be blank")
    @Size(max = 8000)
    String text,

    @NotBlank(message = "The query must not be blank")
    @Size(max = 1000)
    String query
) {
}
