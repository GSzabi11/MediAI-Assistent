package bme.mediai.allergy.api;

import bme.mediai.allergy.persistence.Allergy;

public record AllergyResponse(
        Long id,
        Long patientId,
        String substance,
        Long sourceStatementId
) {
    public static AllergyResponse from(Allergy allergy) {
        return new AllergyResponse(
                allergy.getId(), allergy.getPatientId(), allergy.getSubstance(), allergy.getSourceStatementId());
    }
}
