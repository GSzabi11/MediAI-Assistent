package bme.mediai.chat.service;

import bme.mediai.chat.api.ChatJobRequest;

// Commit UTÁN megy a Pythonnak (a sor csak akkor létezik, ha a tranzakció sikeres)
public record ChatRequestedEvent(Long chatId, ChatJobRequest job) {
}
