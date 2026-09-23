package bme.mediai.medication.api;

import java.math.BigDecimal;

import bme.mediai.medication.persistence.Medication;

public record MedicationResponse(
        Long id,
        Long patientId,
        String name,
        String activeIngredient,
        BigDecimal dose,
        String doseUnit,
        String route,
        Long sourceStatementId,
        boolean active
) {
    public static MedicationResponse from(Medication medication) {
        return new MedicationResponse(
                medication.getId(), medication.getPatientId(), medication.getName(),
                medication.getActiveIngredient(), medication.getDose(), medication.getDoseUnit(),
                medication.getRoute(), medication.getSourceStatementId(), medication.isActive());
    }
}
