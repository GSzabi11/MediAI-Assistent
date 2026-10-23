package bme.mediai.epicrisis.api;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import bme.mediai.epicrisis.persistence.Epicrisis;
import bme.mediai.epicrisis.persistence.EpicrisisStatus;
import bme.mediai.epicrisis.service.EpicrisisHtml;
import bme.mediai.medicalhistory.persistence.MedicalHistory;
import bme.mediai.medicalhistory.service.MedicalHistoryProgressStore;

public record EpicrisisResponse(
        Long id,
        Long patientId,
        Long medicalCareId,
        EpicrisisStatus status,
        Long requestedBy,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt,
        String error,
        // a fejléc (Beteg, TAJ, Dátum) — az adatbázisból, nem szerkeszthető
        EpicrisisHtml.Header header,
        List<Epicrisis.Diagnosis> diagnoses,
        List<Epicrisis.Finding> findings,
        List<MedicalHistory.Medication> medications,
        List<String> recommendations,
        List<String> warnings,
        Model model,
        Map<String, Integer> inputCounts,
        // a kiválasztott leletek és a felhasznált felvételek (a felület ebből jelzi, miből készült)
        List<Long> documentIds,
        List<Long> recordingIds,
        // a törzs (három szakasz) AI-eredetije, ill. a megjelenített változat (az orvosé, ha szerkesztette)
        String aiHtml,
        String html,
        Long editedBy,
        OffsetDateTime editedAt,
        MedicalHistoryProgressStore.Progress progress
) {
    public record Model(String modelName, Long seed, String promptHash, String extractorVersion) {
    }

    public static EpicrisisResponse from(Epicrisis e, EpicrisisHtml.Header header,
            MedicalHistoryProgressStore.Progress progress) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        e.getInput().forEach((k, v) -> counts.put(k.replace("Ids", ""), v.size()));
        String aiHtml = EpicrisisHtml.render(e);
        return new EpicrisisResponse(
                e.getId(), e.getPatientId(), e.getMedicalCareId(), e.getStatus(), e.getRequestedBy(),
                e.getRequestedAt(), e.getCompletedAt(), e.getError(), header, e.getDiagnoses(), e.getFindings(),
                e.getMedications(), e.getRecommendations(), e.getWarnings(),
                e.getModelName() == null ? null
                        : new Model(e.getModelName(), e.getSeed(), e.getPromptHash(), e.getExtractorVersion()),
                counts, e.getInput().getOrDefault("documentIds", List.of()),
                e.getInput().getOrDefault("recordingIds", List.of()),
                aiHtml, e.getEditedHtml() != null ? e.getEditedHtml() : aiHtml,
                e.getEditedBy(), e.getEditedAt(),
                e.getStatus() == EpicrisisStatus.PROCESSING ? progress : null);
    }
}
