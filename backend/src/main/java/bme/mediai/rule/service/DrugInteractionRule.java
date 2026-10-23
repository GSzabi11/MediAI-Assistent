package bme.mediai.rule.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bme.mediai.drug.persistence.DrugReferenceRepository;
import bme.mediai.drug.service.DrugInteractionChecker;
import bme.mediai.drug.service.DrugInteractionChecker.Item;
import bme.mediai.drug.service.DrugInteractionChecker.PairFinding;
import bme.mediai.drug.service.DrugResolver;
import bme.mediai.medication.persistence.Medication;
import bme.mediai.medication.persistence.MedicationRepository;
import bme.mediai.medication.service.MedicationsChangedEvent;
import bme.mediai.statement.persistence.Statement;
import bme.mediai.statement.persistence.StatementProducer;
import bme.mediai.statement.persistence.StatementRepository;
import bme.mediai.statement.persistence.StatementSource;
import bme.mediai.statement.persistence.StatementType;

// Második determinisztikus szabálymotor (CLAUDE.md: gyógyszer-interakciót sosem LLM-mel): a beteg
// aktív gyógyszerei közötti interakció → DETERMINISTIC_RULE állítás, MEDICATION forrással (a két
// gyógyszer-tétel). A tudás a helyi referenciatáblákból jön (PUPHA + DDInter, docs/drug-interaction.md).
//
// Egyeztetés gyógyszer-páronként, mint a referencia-szabálynál: ha egy gyógyszert leállítanak, a rá
// vonatkozó állítások törlődnek (a jóváhagyásukkal együtt — CASCADE); ha a referenciaadat frissítése
// megváltoztatja egy pár szövegét vagy súlyosságát, az állítás újra keletkezik. Idempotens.
@Service
public class DrugInteractionRule {

    public static final String RULE_ID = "drug-interaction";
    public static final String RULE_VERSION = "1";
    static final String LABEL = "Gyógyszer-interakció";

    private static final Logger LOG = LoggerFactory.getLogger(DrugInteractionRule.class);

    private final MedicationRepository medicationRepository;
    private final StatementRepository statementRepository;
    private final DrugReferenceRepository referenceRepository;
    private final DrugResolver resolver;
    private final DrugInteractionChecker checker;

    public DrugInteractionRule(MedicationRepository medicationRepository, StatementRepository statementRepository,
            DrugReferenceRepository referenceRepository, DrugResolver resolver, DrugInteractionChecker checker) {
        this.medicationRepository = medicationRepository;
        this.statementRepository = statementRepository;
        this.referenceRepository = referenceRepository;
        this.resolver = resolver;
        this.checker = checker;
    }

    // Minden gyógyszer-változás után, ugyanabban a tranzakcióban
    @EventListener
    public void onMedicationsChanged(MedicationsChangedEvent event) {
        reconcile(event.patientId());
    }

    private record Wanted(List<Long> medicationIds, String text, PairFinding finding) {
    }

    @Transactional
    public void reconcile(Long patientId) {
        // Betöltetlen referenciaadatnál nem dönthető el semmi — a meglévő állítások maradnak
        if (!referenceRepository.isLoaded()) {
            return;
        }
        List<Medication> meds = medicationRepository.findByPatientIdAndActiveTrueOrderByIdDesc(patientId).stream()
                .sorted(Comparator.comparing(Medication::getId))
                .toList();
        List<Item> items = meds.stream()
                .map(m -> new Item(m.getName(), resolver.resolve(m.getName(), m.getActiveIngredient())))
                .toList();

        Map<List<Long>, Wanted> wanted = new LinkedHashMap<>();
        for (PairFinding f : checker.check(items)) {
            List<Long> ids = List.of(meds.get(f.itemA()).getId(), meds.get(f.itemB()).getId());
            String text = DrugInteractionChecker.text(items.get(f.itemA()).label(), items.get(f.itemB()).label(), f);
            wanted.put(ids, new Wanted(ids, text, f));
        }

        Map<List<Long>, Statement> existing = statementRepository.findMedicationRuleStatements(patientId, RULE_ID).stream()
                .collect(Collectors.toMap(s -> ((StatementSource.Medication) s.getSource()).medicationIds(),
                        Function.identity()));

        List<Statement> stale = existing.entrySet().stream()
                .filter(e -> {
                    Wanted w = wanted.get(e.getKey());
                    return w == null || !w.text().equals(e.getValue().getText())
                            || w.finding().severity() != e.getValue().getSeverity();
                })
                .map(Map.Entry::getValue)
                .toList();
        statementRepository.deleteAll(stale);
        // a törlés előbb fusson le, mint az azonos párra szóló új állítás beszúrása (egyedi index)
        statementRepository.flush();
        stale.forEach(s -> existing.remove(((StatementSource.Medication) s.getSource()).medicationIds()));

        List<Statement> created = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (Wanted w : wanted.values()) {
            if (!existing.containsKey(w.medicationIds())) {
                created.add(new Statement(patientId, StatementType.DETERMINISTIC_RULE, w.text(), LABEL,
                        w.finding().severity(), null, new StatementSource.Medication(w.medicationIds()),
                        new StatementProducer.Rule(RULE_ID, RULE_VERSION), now));
            }
        }
        statementRepository.saveAll(created);
        if (!stale.isEmpty() || !created.isEmpty()) {
            LOG.info("Interakció-szabály, beteg {}: {} új, {} törölt állítás", patientId, created.size(), stale.size());
        }
    }
}
