package bme.mediai.medicalcare.api;

import bme.mediai.medicalcare.persistence.MedicalCareType;
import jakarta.validation.constraints.NotNull;

public record MedicalCareCreateRequest(

    @NotNull(message = "The patientId must not be null")
    Long patientId,

    @NotNull(message = "The userId must not be null")
    Long userId,

    @NotNull(message = "The type must be SBO, OUTPATIENT or ECHO")
    MedicalCareType type
)
{}
