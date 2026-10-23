package bme.mediai.research.api;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// A felület a megnyitott oldal még nem értékelt cikkeit kéri (nem mind előre — docs/literature-research.md)
public record EvaluationRequest(

    @NotEmpty(message = "The articleIds must not be empty")
    @Size(max = 50)
    List<@NotNull Long> articleIds
) {
}
