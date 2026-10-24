package bme.mediai.medicalhistory.service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

// A készülő kórtörténetek haladása ("Dokumentum-kivonat 5/16") — csak memóriában, mint a ChatMessageStore:
// átmeneti állapot, migrációt nem ér meg. A Spring újraindulásakor elveszik; a következő lépés pótolja.
@Component
public class MedicalHistoryProgressStore {

    public record Progress(String message, Integer step, Integer total, String model, OffsetDateTime at) {
    }

    private final Map<Long, Progress> progress = new ConcurrentHashMap<>();

    public void put(Long historyId, String message, Integer step, Integer total, String model) {
        String m = message == null || message.isBlank() ? "Feldolgozás" : message.strip();
        progress.put(historyId, new Progress(m.length() > 500 ? m.substring(0, 500) : m, step, total,
                model == null || model.isBlank() ? null : model.strip(), OffsetDateTime.now()));
    }

    public Progress get(Long historyId) {
        return progress.get(historyId);
    }

    public void remove(Long historyId) {
        progress.remove(historyId);
    }
}
