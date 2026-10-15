package bme.mediai.summary.api;

import java.time.LocalDate;
import java.util.List;

// Spring → Python: a beteg elmúlt 6 hónapjának tényei, a modell bemenete. Név, TAJ, lakcím
// SZÁNDÉKOSAN nincs benne — az összefoglalóhoz nem kell, és a modellnek ne menjen több
// azonosító adat, mint amennyi szükséges. Minden tény saját id-vel jön (a tárolt "input"-hoz).
public record SummaryFacts(
        LocalDate periodStart,
        LocalDate periodEnd,
        Integer ageYears,
        List<LabFact> labs,
        List<FindingFact> findings,
        List<StatementFact> statements,
        List<MedicationFact> medications,
        List<AllergyFact> allergies,
        List<DocumentFact> documents,
        List<RecordingFact> recordings
) {
    // status: "felett" | "alatt" | "eltér" | null (a szabálymotor kiértékelése, nem a modellé)
    public record LabFact(Long id, String name, String value, String unit, String reference, LocalDate date,
            String status) {
    }

    // a determinisztikus szabálymotor állításai (lab-reference-range)
    public record FindingFact(Long id, String text, LocalDate date) {
    }

    // a leletekből kinyert (és orvos által nem elutasított) állítások
    public record StatementFact(Long id, String label, String text, LocalDate date, String reviewStatus) {
    }

    public record MedicationFact(Long id, String name, String activeIngredient, String dose, String route,
            boolean verified) {
    }

    public record AllergyFact(Long id, String substance, boolean verified) {
    }

    // text: a (nem labor-) lelet kinyert szövege, csonkolva. A Python ebből a vizsgálat típusát
    // (Holter, MR, …) és az eredmény-szakaszát olvassa ki; a beteg-fejléc (név, TAJ) a modellhez nem megy
    public record DocumentFact(Long id, String type, LocalDate date, String text) {
    }

    // a hangfelvétel legutóbbi elkészült összefoglalója (az orvos szerkesztett szövege, ha van)
    public record RecordingFact(Long recordingId, Long summaryId, String mode, LocalDate date, String text,
            boolean edited) {
    }
}
