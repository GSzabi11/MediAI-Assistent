package bme.mediai.epicrisis.service;

import bme.mediai.epicrisis.api.EpicrisisFacts;

// Commit UTÁN megy a Pythonnak (a sor csak akkor létezik, ha a tranzakció sikeres)
public record EpicrisisRequestedEvent(Long epicrisisId, EpicrisisFacts facts) {
}
