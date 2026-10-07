package bme.mediai.rule.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bme.mediai.lab.persistence.LabResult;
import bme.mediai.lab.persistence.LabResultRepository;
import bme.mediai.lab.service.LabResultsSavedEvent;
import bme.mediai.lab.service.LabTestNames;
import bme.mediai.statement.persistence.Severity;
import bme.mediai.statement.persistence.Statement;
import bme.mediai.statement.persistence.StatementProducer;
import bme.mediai.statement.persistence.StatementRepository;
import bme.mediai.statement.persistence.StatementSource;
import bme.mediai.statement.persistence.StatementType;

// Első determinisztikus szabálymotor (CLAUDE.md: szabályt sosem LLM-mel): laborérték a
// referenciatartományon kívül → DETERMINISTIC_RULE állítás, LAB forrással (a konkrét mérés).
//
// Egyeztetés vizsgálatonként (egységes név + mértékegység, LabTestNames): állítás azokhoz a
// tartományon kívüli mérésekhez tartozik, amelyek a vizsgálat UTOLSÓ tartományon belüli mérése
// UTÁN jöttek. Ha egy újabb mérés visszakerül a tartományba, a korábbiak állításai törlődnek
// (a jóváhagyásaikkal együtt — CASCADE); a mérés maga megmarad, az állítás belőle újraszámolható.
// Idempotens: többszöri futás ugyanazt az állapotot adja (DB-egyedi index is őrzi).
@Service
public class LabReferenceRangeRule {

    public static final String RULE_ID = "lab-reference-range";
    public static final String RULE_VERSION = "1";

    private static final Logger LOG = LoggerFactory.getLogger(LabReferenceRangeRule.class);

    private final LabResultRepository labResultRepository;
    private final StatementRepository statementRepository;

    public LabReferenceRangeRule(LabResultRepository labResultRepository, StatementRepository statementRepository) {
        this.labResultRepository = labResultRepository;
        this.statementRepository = statementRepository;
    }

    // Minden labormentés után, ugyanabban a tranzakcióban
    @EventListener
    public void onLabResultsSaved(LabResultsSavedEvent event) {
        reconcile(event.patientId());
    }

    @Transactional
    public void reconcile(Long patientId) {
        List<LabResult> results = labResultRepository.findByPatientIdOrderByMeasuredAtDescIdDesc(patientId);

        // vizsgálatonként időrendben
        Map<String, List<LabResult>> byTest = results.stream()
                .sorted(Comparator.comparing(LabResult::getMeasuredAt).thenComparing(LabResult::getId))
                .collect(Collectors.groupingBy(LabReferenceRangeRule::testKey, LinkedHashMap::new, Collectors.toList()));

        Map<Long, LabResult> wanted = new LinkedHashMap<>();
        for (List<LabResult> series : byTest.values()) {
            int lastNormal = -1;
            for (int i = 0; i < series.size(); i++) {
                if (evaluate(series.get(i)) == Verdict.IN_RANGE) {
                    lastNormal = i;
                }
            }
            for (int i = lastNormal + 1; i < series.size(); i++) {
                LabResult r = series.get(i);
                if (evaluate(r).outOfRange()) {
                    wanted.put(r.getId(), r);
                }
            }
        }

        Map<Long, Statement> existing = statementRepository.findLabRuleStatements(patientId, RULE_ID).stream()
                .collect(Collectors.toMap(s -> ((StatementSource.Lab) s.getSource()).labResultId(), Function.identity()));

        List<Statement> stale = existing.entrySet().stream()
                .filter(e -> !wanted.containsKey(e.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        statementRepository.deleteAll(stale);

        List<Statement> created = new ArrayList<>();
        for (LabResult r : wanted.values()) {
            if (!existing.containsKey(r.getId())) {
                created.add(statementFor(r));
            }
        }
        statementRepository.saveAll(created);
        if (!stale.isEmpty() || !created.isEmpty()) {
            LOG.info("Referencia-szabály, beteg {}: {} új, {} törölt állítás", patientId, created.size(), stale.size());
        }
    }

    private static Statement statementFor(LabResult r) {
        return new Statement(
                r.getPatientId(),
                StatementType.DETERMINISTIC_RULE,
                text(r),
                "Labor",
                Severity.WARNING,
                null,
                new StatementSource.Lab(r.getId()),
                new StatementProducer.Rule(RULE_ID, RULE_VERSION),
                r.getMeasuredAt());
    }

    // ------------------------------------------------------------ kiértékelés

    public enum Verdict {
        IN_RANGE, BELOW, ABOVE, DIFFERS, UNKNOWN;

        public boolean outOfRange() {
            return this == BELOW || this == ABOVE || this == DIFFERS;
        }
    }

    // Cenzorált értéknél (">90") csak akkor van döntés, ha a jelből BIZTOSAN következik
    // (">90" és felső határ 60 → felett; ">90" és felső 120 → nem tudható: UNKNOWN).
    public static Verdict evaluate(LabResult r) {
        if (r.getValueText() != null) {
            if (r.getRefText() == null) {
                return Verdict.UNKNOWN;
            }
            return normalize(r.getValueText()).equals(normalize(r.getRefText())) ? Verdict.IN_RANGE : Verdict.DIFFERS;
        }
        BigDecimal v = r.getValueNum();
        BigDecimal low = r.getRefLow();
        BigDecimal high = r.getRefHigh();
        if (v == null || (low == null && high == null)) {
            return Verdict.UNKNOWN;
        }
        String c = r.getComparator();
        boolean below;
        boolean above;
        if (c == null) {
            below = low != null && v.compareTo(low) < 0;
            above = high != null && v.compareTo(high) > 0;
            return below ? Verdict.BELOW : above ? Verdict.ABOVE : Verdict.IN_RANGE;
        }
        // a valódi érték a határon túl van: "<" → kisebb, ">" → nagyobb
        below = low != null && (c.equals("<") ? v.compareTo(low) <= 0 : c.equals("<=") && v.compareTo(low) < 0);
        above = high != null && (c.equals(">") ? v.compareTo(high) >= 0 : c.equals(">=") && v.compareTo(high) > 0);
        if (below) {
            return Verdict.BELOW;
        }
        if (above) {
            return Verdict.ABOVE;
        }
        // biztosan a tartományban, ha a határ a tartomány "jó oldalán" van
        boolean surelyIn = (c.startsWith("<") && (high == null || v.compareTo(high) <= 0) && low == null)
                || (c.startsWith(">") && (low == null || v.compareTo(low) >= 0) && high == null);
        return surelyIn ? Verdict.IN_RANGE : Verdict.UNKNOWN;
    }

    // ------------------------------------------------------------ szöveg (MI)

    static String text(LabResult r) {
        String name = LabTestNames.canonical(r.getName());
        String unit = LabTestNames.canonicalUnit(r.getUnit());
        Verdict v = evaluate(r);
        if (v == Verdict.DIFFERS) {
            return name + ": " + r.getValueText() + " — eltér a referenciától (" + r.getRefText() + ")";
        }
        String value = (r.getComparator() == null ? "" : symbol(r.getComparator())) + number(r.getValueNum())
                + (unit == null ? "" : " " + unit);
        String range = range(r.getRefLow(), r.getRefHigh());
        String where = v == Verdict.BELOW ? "alatt" : "felett";
        return name + " " + value + " — a referenciatartomány (" + range + (unit == null ? "" : " " + unit) + ") " + where;
    }

    private static String range(BigDecimal low, BigDecimal high) {
        if (low != null && high != null) {
            return number(low) + "–" + number(high);
        }
        return low != null ? "> " + number(low) : "< " + number(high);
    }

    // Magyar tizedesvessző, a NUMERIC(12,4) záró nullái nélkül (5.9000 → 5,9)
    private static String number(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String symbol(String c) {
        return switch (c) {
            case "<=" -> "≤";
            case ">=" -> "≥";
            default -> c;
        };
    }

    private static String testKey(LabResult r) {
        String unit = LabTestNames.canonicalUnit(r.getUnit());
        return LabTestNames.canonical(r.getName()).toLowerCase(Locale.ROOT) + "|" + (unit == null ? "" : unit);
    }

    private static String normalize(String s) {
        return s.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
