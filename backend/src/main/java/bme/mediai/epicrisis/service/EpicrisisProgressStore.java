package bme.mediai.epicrisis.service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import bme.mediai.medicalhistory.service.MedicalHistoryProgressStore;

// A készülő epikrízisek haladása — csak memóriában, mint a MedicalHistoryProgressStore (ugyanaz a Progress)
@Component
public class EpicrisisProgressStore {

    private final Map<Long, MedicalHistoryProgressStore.Progress> progress = new ConcurrentHashMap<>();

    public void put(Long epicrisisId, String message, Integer step, Integer total) {
        String m = message == null || message.isBlank() ? "Feldolgozás" : message.strip();
        progress.put(epicrisisId, new MedicalHistoryProgressStore.Progress(m.length() > 500 ? m.substring(0, 500) : m,
                step, total, OffsetDateTime.now()));
    }

    public MedicalHistoryProgressStore.Progress get(Long epicrisisId) {
        return progress.get(epicrisisId);
    }

    public void remove(Long epicrisisId) {
        progress.remove(epicrisisId);
    }
}
