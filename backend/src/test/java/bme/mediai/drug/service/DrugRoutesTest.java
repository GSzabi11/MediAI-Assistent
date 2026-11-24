package bme.mediai.drug.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

// Regresszió (2026-10-24, 6 FAILED dokumentum: „duplicate element: bisoprolol / softacort / tobrex”): a gyógyszer
// szövegében egy szó többször is előfordulhat, a felismerés ezen nem bukhat el (korábban Set.of dobott).
class DrugRoutesTest {

    @Test
    void repeatedWordDoesNotThrow() {
        assertNull(assertDoesNotThrow(() -> DrugRoutes.detect("Bisoprolol 5 mg tabletta, bisoprolol")));
    }

    @Test
    void repeatedWordStillDetectsRoute() {
        assertEquals("ophthalmic", assertDoesNotThrow(() -> DrugRoutes.detect("SOFTACORT szemcsepp softacort szemcsepp")));
        assertEquals("ophthalmic", assertDoesNotThrow(() -> DrugRoutes.detect("Tobrex szemcsepp, Tobrex")));
    }
}
