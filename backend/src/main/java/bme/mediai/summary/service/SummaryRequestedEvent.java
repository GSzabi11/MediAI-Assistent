package bme.mediai.summary.service;

import bme.mediai.summary.api.SummaryFacts;

// Commit UTÁN megy a Pythonnak (a sor csak akkor létezik, ha a tranzakció sikeres)
public record SummaryRequestedEvent(Long summaryId, SummaryFacts facts) {
}
