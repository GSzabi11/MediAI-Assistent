package bme.mediai.medicalhistory.api;

// Python → Spring: POST /api/medical-histories/{id}/progress — a készülő kórtörténet aktuális lépése
public record MedicalHistoryProgressRequest(String message, Integer step, Integer total) {
}
