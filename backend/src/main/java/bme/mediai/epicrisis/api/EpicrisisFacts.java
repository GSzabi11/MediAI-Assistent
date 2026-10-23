package bme.mediai.epicrisis.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import bme.mediai.medicalhistory.api.MedicalHistoryFacts;
import bme.mediai.summary.api.SummaryFacts;

// Spring → Python: az epikrízis bemenete. Név, TAJ, lakcím SZÁNDÉKOSAN nincs benne (a szövegekben is
// maszkolva) — a fejlécet a Spring teszi rá az adatbázisból.
public record EpicrisisFacts(
        LocalDate today,
        Integer ageYears,
        Care care,
        // a kiválasztott nem labor-leletek (a Python kivonatot készít belőlük, ha nincs érvényes)
        List<MedicalHistoryFacts.DocumentInput> documents,
        // a kiválasztott laborleletek: a szabálymotor szerinti eltérések
        List<MedicalHistoryFacts.LabEntry> labs,
        // az ellátás kész felvételeinek átirata
        List<RecordingInput> recordings,
        List<SummaryFacts.MedicationFact> medications,
        List<SummaryFacts.AllergyFact> allergies
) {
    public record Care(String type, LocalDate start, LocalDate end) {
    }

    // truncated: az átirat vége terjedelmi okból kimaradt
    public record RecordingInput(Long id, String mode, OffsetDateTime recordedAt, boolean truncated,
            List<UtteranceInput> utterances) {
    }

    // text: a (javított) megszólalás, a beteg nevével / TAJ-ával maszkolva
    public record UtteranceInput(Integer turnId, String speaker, String text) {
    }
}
