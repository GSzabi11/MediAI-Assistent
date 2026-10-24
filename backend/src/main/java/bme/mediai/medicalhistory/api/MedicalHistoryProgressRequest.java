package bme.mediai.medicalhistory.api;

// Python → Spring: POST /api/medical-histories/{id}/progress — a készülő kórtörténet aktuális lépése
// model: a lépést ténylegesen végző modell (a docproc .env-je szerint) — régi docproc nem küldi
public record MedicalHistoryProgressRequest(String message, Integer step, Integer total, String model) {
}
