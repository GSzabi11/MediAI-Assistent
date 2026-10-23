package bme.mediai.medicalhistory.api;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import bme.mediai.medicalhistory.persistence.MedicalHistory;
import bme.mediai.medicalhistory.persistence.MedicalHistoryStatus;
import bme.mediai.medicalhistory.service.MedicalHistoryHtml;
import bme.mediai.medicalhistory.service.MedicalHistoryProgressStore;

public record MedicalHistoryResponse(
        Long id,
        Long patientId,
        MedicalHistoryStatus status,
        Long requestedBy,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt,
        String error,
        String summary,
        List<MedicalHistory.TimelineItem> timeline,
        List<MedicalHistory.Diagnosis> diagnoses,
        List<MedicalHistory.Medication> medications,
        List<String> recommendations,
        // ellenőrizendő pontok (idézet nélküli elem, nem a leletből származó BNO-kód, ellenőrizetlen szám, …)
        List<String> warnings,
        Model model,
        Map<String, Integer> inputCounts,
        // az AI eredetije a szerkesztő formájában (HTML)
        String aiHtml,
        // a megjelenített / exportált szöveg: az orvos szerkesztése, ha van, különben aiHtml
        String html,
        Long editedBy,
        OffsetDateTime editedAt,
        // a kórtörténet óta új dokumentum/állítás érkezett — érdemes újragenerálni
        boolean stale,
        // PROCESSING alatt: a feldolgozás aktuális lépése (csak memóriában; lehet null)
        MedicalHistoryProgressStore.Progress progress
) {
    public record Model(String modelName, Long seed, String promptHash, String extractorVersion) {
    }

    public static MedicalHistoryResponse from(MedicalHistory h, boolean stale) {
        return from(h, stale, null);
    }

    public static MedicalHistoryResponse from(MedicalHistory h, boolean stale,
            MedicalHistoryProgressStore.Progress progress) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        h.getInput().forEach((k, v) -> counts.put(k.replace("Ids", ""), v.size()));
        String aiHtml = MedicalHistoryHtml.render(h);
        return new MedicalHistoryResponse(
                h.getId(), h.getPatientId(), h.getStatus(), h.getRequestedBy(), h.getRequestedAt(),
                h.getCompletedAt(), h.getError(), h.getSummary(), h.getTimeline(), h.getDiagnoses(),
                h.getMedications(), h.getRecommendations(), h.getWarnings(),
                h.getModelName() == null ? null
                        : new Model(h.getModelName(), h.getSeed(), h.getPromptHash(), h.getExtractorVersion()),
                counts, aiHtml, h.getEditedHtml() != null ? h.getEditedHtml() : aiHtml,
                h.getEditedBy(), h.getEditedAt(), stale,
                h.getStatus() == MedicalHistoryStatus.PROCESSING ? progress : null);
    }
}
