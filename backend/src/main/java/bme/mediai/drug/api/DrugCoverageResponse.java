package bme.mediai.drug.api;

import java.util.List;

import bme.mediai.drug.service.DrugResolver.Resolution;

// A beteg aktív gyógyszerei: melyikhez talált a rendszer hatóanyagot és interakciós adatot. Ahol nincs
// (NOT_FOUND, NO_DATA), ott a riasztás hiánya nem jelenti, hogy nincs interakció — a frontend ezt jelzi.
public record DrugCoverageResponse(boolean loaded, List<MedicationCoverage> medications) {

    public record MedicationCoverage(Long medicationId, String name, Resolution resolution) {
    }
}
