package bme.mediai.allergy.api;

import bme.mediai.allergy.persistence.Allergy;
import bme.mediai.common.persistence.EntryOrigin;

public record AllergyResponse(
        Long id,
        Long patientId,
        String substance,
        Long sourceStatementId,
        EntryOrigin origin,
        // false: a dokumentum-feldolgozás írta be, és a forrás-állítást orvos még nem fogadta el
        boolean verified
) {
    public static AllergyResponse from(Allergy allergy, boolean verified) {
        return new AllergyResponse(
                allergy.getId(), allergy.getPatientId(), allergy.getSubstance(), allergy.getSourceStatementId(),
                allergy.getOrigin(), verified);
    }
}
