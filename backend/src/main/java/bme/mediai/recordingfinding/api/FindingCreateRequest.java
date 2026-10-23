package bme.mediai.recordingfinding.api;

import java.util.List;

import bme.mediai.recordingfinding.persistence.FindingCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// Az orvos új eleme egy csoportban (a userId a kérésből jön — még nincs bejelentkezés). turnIds: ha az
// átirat kijelöléséből jön, a megszólalás; kézi beírásnál üres.
public record FindingCreateRequest(

    @NotNull(message = "The userId must not be null")
    Long userId,

    @NotNull(message = "The category must not be null")
    FindingCategory category,

    @NotBlank(message = "The text must not be blank")
    @Size(max = 500)
    String text,

    @Size(max = 10)
    String bnoCode,

    @Size(max = 20)
    List<@NotNull @PositiveOrZero Integer> turnIds
) {
}
