package bme.mediai.medicalhistory.api;

import jakarta.validation.constraints.NotNull;

public record MedicalHistoryCreateRequest(

    @NotNull(message = "The patientId must not be null")
    Long patientId,

    // ki kérte (bejelentkezés nélkül a kliens adja — nem bizonyító erejű)
    Long userId
)
{}
