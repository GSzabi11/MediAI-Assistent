package bme.mediai.research.api;

import jakarta.validation.constraints.NotNull;

public record ResearchContextCreateRequest(

    @NotNull(message = "The patientId must not be null")
    Long patientId,

    // ki kérte (bejelentkezés nélkül a kliens adja — nem bizonyító erejű)
    Long userId
) {
}
