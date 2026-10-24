package bme.mediai.drug.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import bme.mediai.drug.persistence.DrugReferenceRepository.Product;

// Helyi alkalmazási mód felismerése a gyógyszerforma szavaiból (szemcsepp, krém, orrspray, fülcsepp) —
// a DDInter ezeket külön hatóanyagként vezeti ("Hydrocortisone (ophthalmic)"). Determinisztikus
// szólista; ha a tétel saját szövege nem dönt, a PUPHA-találatok formája, ha mind egyforma.
// A tapasz (transzdermális) szándékosan nincs benne: az szisztémás.
final class DrugRoutes {

    // A DDInter-utótag → a formát jelző szavak (DrugNames.words alakban, ékezet nélkül). Sorrend számít:
    // a "szemgel" előbb a szem, mint a "gel" a bőr.
    private static final Map<String, List<String>> WORDS = new java.util.LinkedHashMap<>();
    static {
        WORDS.put("ophthalmic", List.of("szemcsepp", "szemcseppek", "szemkenocs", "szemgel", "szembe", "szemeszeti",
                "ophthalmic", "eye"));
        WORDS.put("otic", List.of("fulcsepp", "fulbe", "otic"));
        WORDS.put("nasal", List.of("orrspray", "orrcsepp", "orrba", "orrgel", "nasal"));
        WORDS.put("topical", List.of("krem", "kenocs", "gel", "bore", "borre", "kulsoleges", "kutan", "dermalis",
                "hab", "testapolo", "topical"));
    }

    private DrugRoutes() {
    }

    static String detect(String ownText, List<Product> products) {
        String own = detect(ownText);
        if (own != null) {
            return own;
        }
        if (products.isEmpty()) {
            return null;
        }
        Set<String> routes = products.stream().map(p -> String.valueOf(detect(p.name()))).collect(Collectors.toSet());
        return routes.size() == 1 && !routes.contains("null") ? routes.iterator().next() : null;
    }

    static String detect(String text) {
        // HashSet, nem Set.of: a szövegben egy szó többször is előfordulhat ("Bisoprolol … bisoprolol")
        Set<String> words = new HashSet<>(Arrays.asList(DrugNames.words(text).split(" ")));
        for (var e : WORDS.entrySet()) {
            if (e.getValue().stream().anyMatch(words::contains)) {
                return e.getKey();
            }
        }
        return null;
    }
}
