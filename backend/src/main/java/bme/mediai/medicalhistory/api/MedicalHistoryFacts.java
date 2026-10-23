package bme.mediai.medicalhistory.api;

import java.time.LocalDate;
import java.util.List;

import bme.mediai.medicalhistory.persistence.DocumentDigest;
import bme.mediai.summary.api.SummaryFacts;

// Spring → Python: a kórtörténet bemenete, a beteg TELJES előzménye (nem csak 6 hónap). Név, TAJ,
// lakcím SZÁNDÉKOSAN nincs benne (mint a SummaryFacts-ben) — a DOCX-fejlécet a Spring teszi rá.
public record MedicalHistoryFacts(
        LocalDate today,
        Integer ageYears,
        // a nem labor-dokumentumok: a Python kivonatot készít belőlük, ha nincs érvényes
        List<DocumentInput> documents,
        // laborleletenként a szabálymotor szerinti eltérések (determinisztikus idővonal-elem)
        List<LabEntry> labs,
        List<SummaryFacts.MedicationFact> medications,
        List<SummaryFacts.AllergyFact> allergies
) {
    // digest: a meglévő kivonat (a Python újraszámolja, ha a promptHash-e nem a mostani)
    public record DocumentInput(Long id, String type, LocalDate date, String text, Digest digest) {
    }

    public record Digest(String title, String place, String summary, String quote, List<DocumentDigest.Event> events,
            List<DocumentDigest.Diagnosis> diagnoses, List<String> warnings, String promptHash) {
    }

    // abnormal: "Kreatinin: 187 µmol/L (ref 62–106) [felett]" — a minősítés a szabálymotoré, nem a modellé
    public record LabEntry(Long documentId, LocalDate date, List<String> abnormal, int total) {
    }
}
