package bme.mediai.document.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

// Nincs text mező: a szöveget a Spring vágja ki a pozíciók alapján.
public record ChunkRequest(

    @NotNull(message = "The charStart must not be null")
    @PositiveOrZero
    Integer charStart,

    @NotNull(message = "The charEnd must not be null")
    Integer charEnd,

    @NotNull(message = "The embedding must not be null")
    float[] embedding
)
{}
