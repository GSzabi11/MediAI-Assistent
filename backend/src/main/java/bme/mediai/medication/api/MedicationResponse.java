package bme.mediai.medication.api;

import java.math.BigDecimal;

import bme.mediai.common.persistence.EntryOrigin;
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
        boolean active,
        EntryOrigin origin,
        // false: a dokumentum-feldolgozás írta be, és a forrás-állítást orvos még nem fogadta el
        // (vagy elutasította). A frontendnek ezt "nem ellenőrzött"-ként kell jelölnie.
        boolean verified
) {
    public static MedicationResponse from(Medication medication, boolean verified) {
        return new MedicationResponse(
                medication.getId(), medication.getPatientId(), medication.getName(),
                medication.getActiveIngredient(), medication.getDose(), medication.getDoseUnit(),
                medication.getRoute(), medication.getSourceStatementId(), medication.isActive(),
                medication.getOrigin(), verified);
    }
}
