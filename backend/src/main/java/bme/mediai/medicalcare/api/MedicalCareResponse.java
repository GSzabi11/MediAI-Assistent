package bme.mediai.medicalcare.api;

import java.time.OffsetDateTime;

import bme.mediai.medicalcare.persistence.MedicalCare;
import bme.mediai.medicalcare.persistence.MedicalCareType;

public record MedicalCareResponse(
        Long id,
        Long patientId,
        Long userId,
        MedicalCareType type,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt
) {
    public static MedicalCareResponse from(MedicalCare medicalCare) {
        return new MedicalCareResponse(
                medicalCare.getId(), medicalCare.getPatientId(), medicalCare.getUserId(),
                medicalCare.getType(), medicalCare.getStartedAt(), medicalCare.getEndedAt());
    }
}
