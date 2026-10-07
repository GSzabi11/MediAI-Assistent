package bme.mediai.lab.service;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

// Egységes vizsgálatnév a trendhez (grafikon, név szerinti szűrés). A tárolt lab_result.name a
// leleten álló név marad (provenance) — ez csak az olvasáskor számolt csoportosító név, így a
// már feltöltött leleteken is azonnal hat, újrafeldolgozás nélkül.
//
// Szándékosan KÉZZEL ELLENŐRZÖTT lista, nem heurisztika ("egy szóval hosszabb név + azonos
// referencia"): a referenciatartomány laboronként eltér, és két különböző vizsgálat (pl.
// Bilirubin / Bilirubin direkt) egy trendbe vonása hamis trendet mutatna az orvosnak.
public final class LabTestNames {

    // Synlab: a név utáni "(A)" az akkreditáció jele, nem a vizsgálat része
    private static final Pattern ACCREDITED = Pattern.compile("\\s*\\(A\\)\\s*$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // kulcs: a lelet-beli név (kisbetűs, egységes szóközzel) → egységes név
    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("koleszterin", "Koleszterin összes"),
            Map.entry("koleszterin összes", "Koleszterin összes"),
            // Megj.: az ultraszenzitív (hs-)CRP más méréstartományú módszer; az összevonás orvosi döntés
            Map.entry("c reaktív protein ultraszenzitív", "C reaktív protein (CRP)"),
            Map.entry("c reaktív protein (crp)", "C reaktív protein (CRP)"),
            Map.entry("egfr-epi", "eGFR-EPI"),
            Map.entry("fehérvérsejt szám", "Fehérvérsejtszám"),
            Map.entry("fehérvérsejtszám", "Fehérvérsejtszám"),
            Map.entry("hdl koleszterin", "HDL-koleszterin"),
            Map.entry("hdl-koleszterin", "HDL-koleszterin"),
            Map.entry("kálium (k)", "Kálium"),
            Map.entry("kálium", "Kálium"),
            Map.entry("magnézium (mg)", "Magnézium"),
            Map.entry("magnézium", "Magnézium"),
            // a leleten két sorba tört név ("MPV (Átlagos thrombocyta" / "térfoga")
            Map.entry("mpv (átlagos thrombocyta térfoga", "MPV"),
            Map.entry("mpv (átlagos thrombocyta térfoga)", "MPV"),
            Map.entry("mpv", "MPV"),
            Map.entry("nátrium (na)", "Nátrium"),
            Map.entry("nátrium", "Nátrium"),
            Map.entry("vörösvérsejt szám", "Vörösvérsejtszám"),
            Map.entry("vörösvérsejtszám", "Vörösvérsejtszám"));

    private LabTestNames() {
    }

    public static String canonical(String name) {
        if (name == null) {
            return null;
        }
        String cleaned = name;
        while (ACCREDITED.matcher(cleaned).find()) {
            cleaned = ACCREDITED.matcher(cleaned).replaceAll("");
        }
        cleaned = WHITESPACE.matcher(cleaned).replaceAll(" ").strip();
        return SYNONYMS.getOrDefault(cleaned.toLowerCase(Locale.ROOT), cleaned);
    }

    // Két név ugyanaz a vizsgálat-e (kis/nagybetű-függetlenül, a szinonimákkal együtt)
    public static boolean sameTest(String a, String b) {
        return canonical(a).equalsIgnoreCase(canonical(b));
    }

    // Ugyanannak a mértékegységnek eltérő írásmódjai — CSAK 1:1 megfelelés, átváltás nincs
    // (a Hematokrit % ↔ L/L 100-szoros, az nem ide tartozik). Kulcs: szóközök nélkül, kisbetűvel;
    // a "g/L" (gramm) és a "G/L" (giga) ezért NINCS a listában, azok változatlanul maradnak.
    private static final Map<String, String> UNIT_SYNONYMS = Map.ofEntries(
            Map.entry("giga/l", "G/L"),
            Map.entry("tera/l", "T/L"),
            Map.entry("ml/min/1.73m2", "mL/min/1.73m2"),
            Map.entry("ml/perc/1.73m2", "mL/min/1.73m2"),
            // 1 µU/mL = 1 mIU/L
            Map.entry("uu/ml", "mIU/L"),
            Map.entry("µu/ml", "mIU/L"),
            Map.entry("miu/l", "mIU/L"),
            Map.entry("hpf", "/HPF"),
            Map.entry("/hpf", "/HPF"),
            Map.entry("lpf", "/LPF"),
            Map.entry("/lpf", "/LPF"));

    public static String canonicalUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            return null;
        }
        String compact = WHITESPACE.matcher(unit).replaceAll("");
        return UNIT_SYNONYMS.getOrDefault(compact.toLowerCase(Locale.ROOT), unit.strip());
    }
}
