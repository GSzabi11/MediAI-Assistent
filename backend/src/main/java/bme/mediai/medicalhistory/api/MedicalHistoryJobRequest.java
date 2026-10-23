package bme.mediai.medicalhistory.api;

// Spring → Python: POST {ai-service}/medical-histories (docs/medical-history.md). progressUrl: ide küldi a
// Python a lépésenkénti haladást ("Dokumentum-kivonat 5/16").
public record MedicalHistoryJobRequest(Long historyId, String callbackUrl, String progressUrl,
        MedicalHistoryFacts facts) {
}
