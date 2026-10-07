package bme.mediai.summary.api;

import jakarta.validation.constraints.NotNull;

public record ClinicalSummaryCreateRequest(

    @NotNull(message = "The patientId must not be null")
    Long patientId,

    // ki kérte (bejelentkezés nélkül a kliens adja — nem bizonyító erejű, mint a napló X-User-Id-ja)
    Long userId
)
{}
