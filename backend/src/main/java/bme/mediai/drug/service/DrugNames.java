package bme.mediai.drug.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Gyógyszernevek determinisztikus normalizálása — a PUPHA, a DDInter, az ATC-nevek és a beírt nevek
// ugyanezzel a kulccsal illeszkednek. Tudatosan nincs hasonlóság-alapú (fuzzy) illesztés: egy rossz
// hatóanyag hamis riasztást vagy hamis biztonságot adna. Ami nem illeszkedik, az „nincs adat”.
public final class DrugNames {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    // Kombináció részei: "metformin and vildagliptin", "naloxone / oxycodone", "metformin és vildagliptin"
    // (A "\b" az ékezetes betűt nem tekinti betűnek, ezért a kötőszavakat szóközök határolják.)
    private static final Pattern PARTS = Pattern.compile("\\s*(?:,|/|\\+|;)\\s*|\\s+(?:and|és|with)\\s+");
    // "paracetamol kombinációk", "ibuprofen, combinations": a többi összetevő ismeretlen — külön, nem
    // illeszkedő részként marad, így a leképezés részleges (PARTIAL) lesz, nem teljes
    private static final Pattern COMBINATION = Pattern.compile(
            "(?iu)^(.*?)\\s*(kombinációk|kombinációi|kombináció|combinations|combination)(?:\\s.*)?$");

    // Só- és kristályvíz-utótagok: "levothyroxine sodium" → "levothyroxine". Csak akkor vágjuk le, ha
    // a teljes név nem illeszkedik (a "calcium carbonate" maga is hatóanyag a DDInterben).
    private static final Set<String> SALT_WORDS = Set.of(
            "sodium", "potassium", "hydrochloride", "hydrobromide", "hcl", "maleate", "mesilate", "mesylate",
            "besilate", "besylate", "fumarate", "tartrate", "succinate", "citrate", "acetate", "sulfate",
            "sulphate", "phosphate", "bromide", "chloride", "monohydrate", "dihydrate", "trihydrate",
            "hemihydrate", "anhydrous", "arginine", "erbumine", "tert", "butylamine", "dipropionate",
            "propionate", "furoate", "natrium", "kalium", "hidroklorid", "monohidrat", "dihidrat");

    // Kézzel ellenőrzött szinonimák (kulcs → DDInter-név kulcsa): a magyar / nemzetközi írásmód, amit sem
    // az ATC angol neve, sem az RxNorm nem hoz. Új pár = új sor (mint a LabTestNames-nél).
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("paracetamol", "acetaminophen"),
            Map.entry("mgso4", "magnesiumsulfate"),
            Map.entry("acetilszalicilsav", "acetylsalicylicacid"),
            Map.entry("aspirin", "acetylsalicylicacid"),
            Map.entry("kaptopril", "captopril"),
            Map.entry("prednizon", "prednisone"),
            Map.entry("prednizolon", "prednisolone"),
            Map.entry("noretisteron", "norethisterone"),
            Map.entry("naloxon", "naloxone"),
            Map.entry("atorvastatine", "atorvastatin"),
            Map.entry("rozuvasztatin", "rosuvastatin"),
            Map.entry("szimvasztatin", "simvastatin"),
            Map.entry("klopidogrel", "clopidogrel"),
            Map.entry("amiodaron", "amiodarone"),
            Map.entry("acenokumarol", "acenocoumarol"),
            Map.entry("ethinylestradiolum", "ethinylestradiol"),
            Map.entry("albuterol", "salbutamol"),
            Map.entry("glibenclamide", "glyburide"),
            Map.entry("glibenklamid", "glyburide"),
            Map.entry("furoszemid", "furosemide"),
            Map.entry("spironolakton", "spironolactone"),
            Map.entry("thiamazole", "methimazole"),
            Map.entry("tiamazol", "methimazole"),
            Map.entry("cholestyramineresin", "cholestyramine"),
            Map.entry("levotiroxin", "levothyroxine"),
            Map.entry("levothyroxin", "levothyroxine"));

    private DrugNames() {
    }

    // Kisbetű, ékezet nélkül, csak betű és szám (szóköz sem): "Ethinyl-estradiol" = "ethinylestradiol"
    public static String key(String s) {
        if (s == null) {
            return "";
        }
        return NON_ALNUM.matcher(words(s)).replaceAll("");
    }

    // Kisbetű, ékezet nélkül, szavak egy szóközzel elválasztva
    public static String words(String s) {
        if (s == null) {
            return "";
        }
        String stripped = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        return NON_ALNUM.matcher(stripped.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    // Márkanév-kulcs: a név első, számot még nem tartalmazó szavai, nagybetűvel.
    // "MAREVAN 3 MG TABLETTA" → "MAREVAN", "Algoflex Forte 400 mg" → "ALGOFLEX FORTE"
    public static String brandKey(String name) {
        List<String> head = new ArrayList<>();
        for (String w : words(name).split(" ")) {
            if (w.isEmpty() || DIGIT.matcher(w).find()) {
                break;
            }
            head.add(w);
        }
        return String.join(" ", head).toUpperCase(Locale.ROOT);
    }

    // Kombinációs név részei (eredeti írásmóddal): "metformin and vildagliptin" → [metformin, vildagliptin]
    public static List<String> parts(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String p : PARTS.split(name.strip())) {
            String part = p.strip();
            Matcher m = COMBINATION.matcher(part);
            if (m.matches()) {
                if (!m.group(1).isBlank()) {
                    out.add(m.group(1).strip());
                }
                out.add(m.group(2));
            } else if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    // A kulcs a kézi szinonimatáblából jött-e (és nem a név maga)
    public static boolean viaAlias(String name, String matchedKey) {
        return ALIASES.containsValue(matchedKey) && !matchedKey.equals(key(name));
    }

    // A DDInter-kulcs jelöltjei sorrendben: a teljes kulcs, a szinonimája, majd sóutótag nélkül
    public static List<String> candidateKeys(String name) {
        List<String> keys = new ArrayList<>();
        String k = key(name);
        if (k.isEmpty()) {
            return keys;
        }
        keys.add(k);
        if (ALIASES.containsKey(k)) {
            keys.add(ALIASES.get(k));
        }
        List<String> w = new ArrayList<>(Arrays.asList(words(name).split(" ")));
        boolean cut = false;
        while (w.size() > 1 && SALT_WORDS.contains(w.getLast())) {
            w.removeLast();
            cut = true;
        }
        if (cut) {
            String base = String.join("", w);
            keys.add(base);
            if (ALIASES.containsKey(base)) {
                keys.add(ALIASES.get(base));
            }
        }
        return keys;
    }
}
