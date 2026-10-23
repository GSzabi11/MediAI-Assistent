package bme.mediai.drug.api;

import java.util.List;

import bme.mediai.drug.service.DrugInteractionChecker.IngredientPair;
import bme.mediai.drug.service.DrugResolver.Resolution;
import bme.mediai.statement.persistence.Severity;

// Ad hoc ellenőrzés eredménye (nem tárolódik): a beírt szerek feloldása, a szedett gyógyszereké, és a
// beírt szerek párjai (egymással és a szedettekkel). A szedettek egymás közötti párjai állításként élnek.
public record DrugCheckResponse(
        boolean loaded,
        List<Entry> entered,
        List<Entry> current,
        List<Finding> findings
) {

    // medicationId: szedett gyógyszernél a tétel, beírtnál null
    public record Entry(Long medicationId, String label, Resolution resolution) {
    }

    public record Finding(String labelA, Long medicationIdA, String labelB, Long medicationIdB, Severity severity,
            List<IngredientPair> interactions, List<String> duplicates, String text) {
    }
}
