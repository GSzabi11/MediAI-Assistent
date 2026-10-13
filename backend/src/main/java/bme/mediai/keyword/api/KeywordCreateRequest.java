package bme.mediai.keyword.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Az orvos új kulcsszava (+ gomb). A userId a kérésből jön (még nincs bejelentkezés).
public record KeywordCreateRequest(

    @NotNull(message = "The userId must not be null")
    Long userId,

    @NotBlank(message = "The term must not be blank")
    @Size(max = 200)
    String term
) {
}
