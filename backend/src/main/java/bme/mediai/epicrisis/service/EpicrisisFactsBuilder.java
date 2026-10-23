package bme.mediai.epicrisis.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import bme.mediai.document.persistence.Document;
import bme.mediai.epicrisis.api.EpicrisisFacts;
import bme.mediai.medicalcare.persistence.MedicalCare;
import bme.mediai.medicalhistory.persistence.MedicalHistory;
import bme.mediai.medicalhistory.service.MedicalHistoryFactsBuilder;
import bme.mediai.patient.persistence.Patient;
import bme.mediai.recording.persistence.Recording;
import bme.mediai.recording.persistence.RecordingRepository;
import bme.mediai.recording.persistence.RecordingStatus;
import bme.mediai.utterance.persistence.Utterance;
import bme.mediai.utterance.persistence.UtteranceRepository;

// Az epikrízis bemenete: a kiválasztott leletek (a kórtörténettel közös módon: szöveg + meglévő kivonat,
// laborleletnél a szabálymotor szerinti eltérések), az ellátás kész felvételeinek átirata, a gyógyszer- és
// allergialista. A beteg neve / TAJ-a a szövegekben maszkolva (MedicalHistoryFactsBuilder.mask).
@Component
public class EpicrisisFactsBuilder {

    private static final ZoneId CLINICAL_ZONE = ZoneId.of("Europe/Budapest");
    // felvételenként legfeljebb ennyi karakternyi átirat megy a modellhez (a vége kimarad, figyelmeztetéssel)
    private static final int MAX_TRANSCRIPT_CHARS = 12000;

    public record Built(EpicrisisFacts facts, List<MedicalHistory.Medication> medications,
            Map<String, List<Long>> inputIds) {
    }

    private final MedicalHistoryFactsBuilder historyFacts;
    private final RecordingRepository recordingRepository;
    private final UtteranceRepository utteranceRepository;

    public EpicrisisFactsBuilder(MedicalHistoryFactsBuilder historyFacts, RecordingRepository recordingRepository,
            UtteranceRepository utteranceRepository) {
        this.historyFacts = historyFacts;
        this.recordingRepository = recordingRepository;
        this.utteranceRepository = utteranceRepository;
    }

    // Az ellátás időszakába eső, használható leletek: klinikai dátum a kezdet és a zárás (nyitottnál a mai
    // nap) napja között, budapesti idő szerint. A felület ugyanezt jelöli be alapból.
    public static boolean inCarePeriod(Document d, MedicalCare care) {
        LocalDate start = care.getStartedAt().atZoneSameInstant(CLINICAL_ZONE).toLocalDate();
        LocalDate end = care.getEndedAt() == null ? LocalDate.now(CLINICAL_ZONE)
                : care.getEndedAt().atZoneSameInstant(CLINICAL_ZONE).toLocalDate();
        return d.getClinicalDate() != null && !d.getClinicalDate().isBefore(start) && !d.getClinicalDate().isAfter(end);
    }

    public Built build(Patient patient, MedicalCare care, Set<Long> documentIds) {
        MedicalHistoryFactsBuilder.Built h = historyFacts.build(patient, d -> documentIds.contains(d.getId()));

        List<Recording> recordings = recordingRepository.findByPatientIdOrderByRecordedAtDesc(patient.getId()).stream()
                .filter(r -> care.getId().equals(r.getMedicalCareId()) && r.getStatus() == RecordingStatus.DONE)
                .toList()
                .reversed();
        List<EpicrisisFacts.RecordingInput> recordingInputs = new ArrayList<>();
        for (Recording r : recordings) {
            List<EpicrisisFacts.UtteranceInput> utterances = new ArrayList<>();
            int chars = 0;
            boolean truncated = false;
            for (Utterance u : utteranceRepository.findByRecordingIdOrderByTurnId(r.getId())) {
                String text = MedicalHistoryFactsBuilder.mask(patient, u.getText());
                if (chars + text.length() > MAX_TRANSCRIPT_CHARS) {
                    truncated = true;
                    break;
                }
                chars += text.length();
                utterances.add(new EpicrisisFacts.UtteranceInput(u.getTurnId(), u.getSpeaker().name(), text));
            }
            if (!utterances.isEmpty()) {
                recordingInputs.add(new EpicrisisFacts.RecordingInput(r.getId(), r.getMode().name(), r.getRecordedAt(),
                        truncated, utterances));
            }
        }

        LocalDate start = care.getStartedAt().atZoneSameInstant(CLINICAL_ZONE).toLocalDate();
        LocalDate end = care.getEndedAt() == null ? null : care.getEndedAt().atZoneSameInstant(CLINICAL_ZONE).toLocalDate();
        EpicrisisFacts facts = new EpicrisisFacts(h.facts().today(), h.facts().ageYears(),
                new EpicrisisFacts.Care(care.getType().name(), start, end), h.facts().documents(), h.facts().labs(),
                recordingInputs, h.facts().medications(), h.facts().allergies());

        Map<String, List<Long>> ids = new LinkedHashMap<>();
        ids.put("documentIds", h.inputIds().get("documentIds"));
        ids.put("labResultIds", h.inputIds().get("labResultIds"));
        ids.put("recordingIds", recordingInputs.stream().map(EpicrisisFacts.RecordingInput::id).toList());
        ids.put("medicationIds", h.inputIds().get("medicationIds"));
        ids.put("allergyIds", h.inputIds().get("allergyIds"));
        return new Built(facts, h.medications(), ids);
    }
}
