package bme.mediai.drug.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import bme.mediai.drug.persistence.DrugReferenceRepository;
import bme.mediai.drug.persistence.DrugReferenceRepository.AtcInfo;
import bme.mediai.drug.persistence.DrugReferenceRepository.Ingredient;
import bme.mediai.drug.persistence.DrugReferenceRepository.Product;

// Gyógyszernév (+ beírt hatóanyag) → DDInter-hatóanyagok, determinisztikusan:
//   1. márkanév → PUPHA-készítmények (márkakulcs-egyezés) → ATC-kódok
//   2. a hatóanyag mező (és ha nincs PUPHA-találat, maga a név) részei → DDInter-név vagy ATC-név
//   3. ATC-kódok → DDInter-hatóanyagok (a frissítéskor számolt atc_ddi_drug)
// Hasonlóság-alapú találgatás nincs: ami nem illeszkedik, az „nincs adat”, és ezt a válasz jelzi —
// a riasztás hiánya ilyenkor NEM jelenti, hogy nincs interakció.
@Service
public class DrugResolver {

    public enum Status {
        // minden rész DDInter-hatóanyagra képeződött
        RESOLVED,
        // van hatóanyag, de egy része nem (pl. "perindopril and diuretics", vagy egy ismeretlen összetevő)
        PARTIAL,
        // a készítmény / ATC ismert, de a DDInterben nincs hatóanyaga (pl. EU-specifikus szer)
        NO_DATA,
        // se PUPHA-készítmény, se hatóanyag
        NOT_FOUND
    }

    public record AtcRef(String code, String name) {
    }

    public record Resolution(String input, Status status, List<String> products, List<AtcRef> atcCodes,
            List<Ingredient> ingredients, List<String> unresolved) {
    }

    private static final int MAX_PRODUCTS = 5;
    private static final int MIN_BRAND_KEY = 4;

    private final DrugReferenceRepository repository;

    public DrugResolver(DrugReferenceRepository repository) {
        this.repository = repository;
    }

    public Resolution resolve(String name, String activeIngredient) {
        String input = activeIngredient == null || activeIngredient.isBlank() ? name : name + " (" + activeIngredient.strip() + ")";
        List<Product> products = products(name);
        Set<String> atcCodes = new LinkedHashSet<>();
        products.stream().map(Product::atcCode).filter(a -> a != null && a.length() == 7).forEach(atcCodes::add);

        Map<Integer, Ingredient> ingredients = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        List<String> texts = new ArrayList<>(DrugNames.parts(activeIngredient));
        // a név hatóanyagként csak akkor számít, ha nem márkanév és nincs külön hatóanyag megadva
        if (products.isEmpty() && texts.isEmpty()) {
            DrugNames.parts(name).stream().map(DrugResolver::stripStrength).filter(t -> !t.isBlank()).forEach(texts::add);
        }
        for (String part : texts) {
            boolean hit = false;
            for (String key : DrugNames.candidateKeys(part)) {
                var direct = repository.ingredientByKey(key);
                if (direct.isPresent()) {
                    ingredients.putIfAbsent(direct.get().id(), direct.get());
                    hit = true;
                    break;
                }
                List<AtcInfo> atcs = repository.atcByNameKey(key);
                if (!atcs.isEmpty()) {
                    atcs.forEach(a -> atcCodes.add(a.code()));
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                unresolved.add(part);
            }
        }

        List<AtcInfo> atcInfos = repository.atcByCodes(atcCodes);
        List<Integer> fromAtc = repository.ingredientsOfAtc(atcCodes).stream()
                .map(DrugReferenceRepository.AtcIngredient::ddiId).toList();
        repository.ingredientsByIds(fromAtc).forEach(i -> ingredients.putIfAbsent(i.id(), i));
        atcInfos.stream().filter(a -> !"FULL".equals(a.mapping()))
                .forEach(a -> unresolved.add(a.code() + " " + (a.nameEn() != null ? a.nameEn() : a.nameHu())));

        Status status;
        if (ingredients.isEmpty()) {
            status = products.isEmpty() && atcCodes.isEmpty() ? Status.NOT_FOUND : Status.NO_DATA;
        } else {
            status = unresolved.isEmpty() ? Status.RESOLVED : Status.PARTIAL;
        }
        List<AtcRef> atcRefs = atcInfos.stream()
                .map(a -> new AtcRef(a.code(), a.nameHu() != null ? a.nameHu() : a.nameEn()))
                .toList();
        return new Resolution(input, status,
                products.stream().map(Product::name).distinct().limit(MAX_PRODUCTS).toList(),
                atcRefs, List.copyOf(ingredients.values()), unresolved);
    }

    // Márkakulcs-egyezés, majd a márkakulcs eleje ("ASPIRIN PLUS C" → "ASPIRIN PLUS C PEZSGOTABLETTA"); ha nincs, a végéről szavanként rövidítve ("ALGOFLEX FORTE DOLO" → "ALGOFLEX FORTE"
    // → "ALGOFLEX"), de legalább 4 betűig. Forgalomban lévő találat esetén csak azok számítanak.
    private List<Product> products(String name) {
        List<String> words = new ArrayList<>(Arrays.asList(DrugNames.brandKey(name).split(" ")));
        while (!words.isEmpty()) {
            String key = String.join(" ", words);
            if (key.replace(" ", "").length() < MIN_BRAND_KEY) {
                break;
            }
            List<Product> found = repository.productsByBrandKey(key);
            if (found.isEmpty()) {
                found = repository.productsByBrandKeyPrefix(key);
            }
            if (!found.isEmpty()) {
                List<Product> onMarket = found.stream().filter(Product::onMarket).toList();
                return onMarket.isEmpty() ? found : onMarket;
            }
            words.removeLast();
        }
        return List.of();
    }

    // "ibuprofen 400 mg" → "ibuprofen": hatóanyagként értelmezve az erősség nem része a névnek
    private static String stripStrength(String name) {
        String key = DrugNames.brandKey(name);
        return key.isEmpty() ? name : key.toLowerCase();
    }
}
