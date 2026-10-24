package bme.mediai.drug.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import bme.mediai.drug.persistence.DrugReferenceRepository;
import bme.mediai.drug.persistence.DrugReferenceRepository.Ingredient;
import bme.mediai.drug.persistence.DrugReferenceRepository.Interaction;
import bme.mediai.statement.persistence.Severity;

// Páronkénti ellenőrzés: két gyógyszer (tétel) között interakció, ha valamelyik hatóanyaguk-párja
// szerepel a DDInterben. Egy hatóanyagot önmagával nem vetünk össze (két tétel azonos hatóanyaga nem
// riasztás — Szabi kérése, 2026-10-24), és egy készítményen belüli hatóanyagok (kombináció) sem riasztanak.
@Service
public class DrugInteractionChecker {

    public static final String SOURCE_NAME = "DDInter 2.0";

    public record Item(String label, DrugResolver.Resolution resolution) {
    }

    public record IngredientPair(String ingredientA, String ingredientB, String level, String levelHu) {
    }

    public record PairFinding(int itemA, int itemB, Severity severity, List<IngredientPair> interactions) {
    }

    // DDInter-súlyosság → állítás-súlyosság. Az „Unknown” (a párok ~20%-a) ismert, de klinikailag nem besorolt
    // interakció: tájékoztató — figyelmeztetésként a lista nagy részét adná, és elfedné a valódi riasztásokat.
    private static final Map<String, Severity> SEVERITY = Map.of(
            "Major", Severity.CRITICAL, "Moderate", Severity.WARNING, "Unknown", Severity.INFO, "Minor", Severity.INFO);
    private static final Map<String, String> LEVEL_HU = Map.of(
            "Major", "súlyos", "Moderate", "közepes", "Minor", "enyhe", "Unknown", "ismeretlen súlyosságú");
    private static final List<String> ORDER = List.of("Major", "Moderate", "Minor", "Unknown");

    private final DrugReferenceRepository repository;

    public DrugInteractionChecker(DrugReferenceRepository repository) {
        this.repository = repository;
    }

    public List<PairFinding> check(List<Item> items) {
        Set<Integer> all = new LinkedHashSet<>();
        items.forEach(it -> it.resolution().ingredients().forEach(i -> all.add(i.id())));
        Map<Long, String> levels = new HashMap<>();
        for (Interaction x : repository.interactionsAmong(all)) {
            levels.put(pairKey(x.drugA(), x.drugB()), x.level());
        }

        List<PairFinding> findings = new ArrayList<>();
        for (int a = 0; a < items.size(); a++) {
            for (int b = a + 1; b < items.size(); b++) {
                PairFinding f = compare(a, b, items.get(a), items.get(b), levels);
                if (f != null) {
                    findings.add(f);
                }
            }
        }
        findings.sort(Comparator.comparing((PairFinding f) -> f.severity()).reversed());
        return findings;
    }

    private static PairFinding compare(int ia, int ib, Item a, Item b, Map<Long, String> levels) {
        List<IngredientPair> pairs = new ArrayList<>();
        for (Ingredient x : a.resolution().ingredients()) {
            for (Ingredient y : b.resolution().ingredients()) {
                if (x.id() == y.id()) {
                    continue;
                }
                String level = levels.get(pairKey(x.id(), y.id()));
                if (level != null) {
                    pairs.add(new IngredientPair(x.name(), y.name(), level, LEVEL_HU.get(level)));
                }
            }
        }
        if (pairs.isEmpty()) {
            return null;
        }
        pairs.sort(Comparator.comparing(p -> ORDER.indexOf(p.level())));
        return new PairFinding(ia, ib, SEVERITY.get(pairs.getFirst().level()), pairs);
    }

    // Az orvosnak mutatott szöveg (MI): mindkét gyógyszer, az érintett hatóanyag-párok, és a forrás
    public static String text(String labelA, String labelB, PairFinding f) {
        StringBuilder sb = new StringBuilder(labelA).append(" + ").append(labelB).append(": ");
        List<String> parts = new ArrayList<>();
        for (IngredientPair p : f.interactions()) {
            parts.add(p.levelHu() + " interakció (" + p.ingredientA() + " – " + p.ingredientB() + ")");
        }
        return sb.append(String.join("; ", parts)).append(". Forrás: ").append(SOURCE_NAME).toString();
    }

    private static long pairKey(int x, int y) {
        return ((long) Math.min(x, y) << 32) | Math.max(x, y);
    }
}
