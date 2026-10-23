package bme.mediai.drug.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bme.mediai.drug.api.DrugCheckResponse;
import bme.mediai.drug.api.DrugCoverageResponse;
import bme.mediai.drug.api.DrugReferenceStatusResponse;
import bme.mediai.drug.persistence.DrugReferenceRepository;
import bme.mediai.drug.service.DrugInteractionChecker.Item;
import bme.mediai.drug.service.DrugInteractionChecker.PairFinding;
import bme.mediai.medication.persistence.Medication;
import bme.mediai.medication.persistence.MedicationRepository;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.PatientRepository;

// Az API mögötti olvasó műveletek: a referenciaadat állapota, a beteg gyógyszereinek lefedettsége
// (melyikhez van interakciós adat), és az ad hoc ellenőrzés (az orvos által beírt 1–5 gyógyszer a beteg
// aktív gyógyszereivel és egymással). Az ad hoc eredmény NEM tárolódik — a tárolt riasztás az aktív
// gyógyszerek közötti, a szabálymotor állítása (DrugInteractionRule).
@Service
public class DrugInteractionService {

    private final DrugReferenceRepository referenceRepository;
    private final DrugResolver resolver;
    private final DrugInteractionChecker checker;
    private final MedicationRepository medicationRepository;
    private final PatientRepository patientRepository;
    private final DrugReferenceRefresh refresh;

    public DrugInteractionService(DrugReferenceRepository referenceRepository, DrugResolver resolver,
            DrugInteractionChecker checker, MedicationRepository medicationRepository,
            PatientRepository patientRepository, DrugReferenceRefresh refresh) {
        this.referenceRepository = referenceRepository;
        this.resolver = resolver;
        this.checker = checker;
        this.medicationRepository = medicationRepository;
        this.patientRepository = patientRepository;
        this.refresh = refresh;
    }

    public DrugReferenceStatusResponse status() {
        return new DrugReferenceStatusResponse(referenceRepository.isLoaded(), refresh.isRunning(),
                refresh.runningSince(), refresh.lastRun(), referenceRepository.latestImports());
    }

    public List<String> suggestions(String query) {
        String key = DrugNames.brandKey(query);
        if (key.length() < 2) {
            return List.of();
        }
        return referenceRepository.brandSuggestions(key, 15);
    }

    @Transactional(readOnly = true)
    public DrugCoverageResponse coverage(Long patientId) {
        List<Medication> meds = activeMeds(patientId);
        boolean loaded = referenceRepository.isLoaded();
        List<DrugCoverageResponse.MedicationCoverage> rows = loaded
                ? meds.stream().map(m -> new DrugCoverageResponse.MedicationCoverage(m.getId(), m.getName(),
                        resolver.resolve(m.getName(), m.getActiveIngredient()))).toList()
                : List.of();
        return new DrugCoverageResponse(loaded, rows);
    }

    @Transactional(readOnly = true)
    public DrugCheckResponse check(Long patientId, List<String> drugs) {
        List<Medication> meds = activeMeds(patientId);
        if (!referenceRepository.isLoaded()) {
            return new DrugCheckResponse(false, List.of(), List.of(), List.of());
        }
        List<DrugCheckResponse.Entry> entries = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        for (String d : drugs) {
            DrugResolver.Resolution r = resolver.resolve(d.strip(), null);
            entries.add(new DrugCheckResponse.Entry(null, d.strip(), r));
            items.add(new Item(d.strip(), r));
        }
        int entered = items.size();
        List<DrugCheckResponse.Entry> current = new ArrayList<>();
        for (Medication m : meds) {
            DrugResolver.Resolution r = resolver.resolve(m.getName(), m.getActiveIngredient());
            current.add(new DrugCheckResponse.Entry(m.getId(), m.getName(), r));
            items.add(new Item(m.getName(), r));
        }

        List<DrugCheckResponse.Finding> findings = new ArrayList<>();
        for (PairFinding f : checker.check(items)) {
            // a szedett gyógyszerek egymás közötti párjai már állításként élnek — itt csak az új szerek
            if (f.itemA() >= entered && f.itemB() >= entered) {
                continue;
            }
            DrugCheckResponse.Entry a = f.itemA() < entered ? entries.get(f.itemA()) : current.get(f.itemA() - entered);
            DrugCheckResponse.Entry b = f.itemB() < entered ? entries.get(f.itemB()) : current.get(f.itemB() - entered);
            findings.add(new DrugCheckResponse.Finding(a.label(), a.medicationId(), b.label(), b.medicationId(),
                    f.severity(), f.interactions(), f.duplicates(),
                    DrugInteractionChecker.text(a.label(), b.label(), f)));
        }
        return new DrugCheckResponse(true, entries, current, findings);
    }

    private List<Medication> activeMeds(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
        return medicationRepository.findByPatientIdAndActiveTrueOrderByIdDesc(patientId).stream()
                .sorted(Comparator.comparing(Medication::getId))
                .toList();
    }
}
