package bme.mediai.medicalhistory.service;

import bme.mediai.medicalhistory.api.MedicalHistoryFacts;

// Commit UTÁN megy a Pythonnak (a sor csak akkor létezik, ha a tranzakció sikeres)
public record MedicalHistoryRequestedEvent(Long historyId, MedicalHistoryFacts facts) {
}
