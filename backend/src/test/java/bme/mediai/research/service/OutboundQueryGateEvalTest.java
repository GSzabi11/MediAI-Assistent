package bme.mediai.research.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import bme.mediai.patient.persistence.Patient;

// A kimenő kapu mérése (IDK C4. táblázat, M1): a tervben rögzített tesztvektorok egy szintetikus betegen.
// A fő készlet (tiltandó + átengedendő) elvárt kimenetét ellenőrzi; a kiegészítő készlet csak mérés (a kapu
// ismert határai), nem bukik el rajta. A táblázatot a szabványos kimenetre írja:
//   mvnw -q test -Dtest=OutboundQueryGateEvalTest
class OutboundQueryGateEvalTest {

    private record Vector(String group, String label, String query, boolean shouldBlock) {}

    // kitalált beteg — a TAJ formailag 9 számjegy, létező személyhez nem tartozik
    private final Patient patient = new Patient("Kovács Erzsébet", "Nagy Ilona", LocalDate.of(1956, 3, 14),
            "123456788", "1117 Budapest, Irinyi József utca 4.");
    private final OutboundQueryGate gate = new OutboundQueryGate();

    private static final List<Vector> MAIN = List.of(
            new Vector("tiltandó", "név", "Kovács Erzsébet heart failure", true),
            new Vector("tiltandó", "anyja neve", "heart failure Nagy Ilona", true),
            new Vector("tiltandó", "lakcím", "heart failure Irinyi utca", true),
            new Vector("tiltandó", "TAJ, egyben", "heart failure 123456788", true),
            new Vector("tiltandó", "TAJ, szóközzel", "heart failure 123 456 788", true),
            new Vector("tiltandó", "TAJ, kötőjellel", "heart failure 123-456-788", true),
            new Vector("tiltandó", "születési dátum, ISO", "heart failure 1956-03-14", true),
            new Vector("tiltandó", "születési dátum, pontokkal", "heart failure 1956.03.14", true),
            new Vector("tiltandó", "hosszú számsor", "heart failure 5512345", true),
            new Vector("tiltandó", "pontos életkor", "70 éves heart failure", true),
            new Vector("átengedendő", "MeSH + szinonimák", "Heart Failure[MeSH Terms] AND (SGLT2 inhibitors OR dapagliflozin)", false),
            new Vector("átengedendő", "gyógyszer dózissal", "metformin 1000 mg AND lactic acidosis", false),
            new Vector("átengedendő", "évszámszűrő", "atrial fibrillation AND apixaban AND 2020:2025[dp]", false),
            new Vector("átengedendő", "MeSH-jelölő vesszővel", "Diabetes Mellitus, Type 2[MeSH Terms]", false),
            new Vector("átengedendő", "életkori MeSH-szűrő", "Heart Failure[MeSH Terms] AND Aged[MeSH Terms]", false),
            new Vector("átengedendő", "laborérték", "chronic kidney disease AND eGFR 30", false),
            new Vector("átengedendő", "diagnózis + gyógyszer", "COPD exacerbation AND roflumilast", false),
            new Vector("átengedendő", "két fogalom VAGY-gyal", "Gastroesophageal Reflux AND (palpitations OR arrhythmia)", false));

    // a kapu ismert határai: csak mérjük, nem követelmény (a C4-ben nem szerepelnek)
    private static final List<Vector> EXTRA = List.of(
            new Vector("kiegészítő", "név ékezet nélkül, fordítva", "Erzsebet Kovacs heart failure", true),
            new Vector("kiegészítő", "név nagybetűvel", "KOVÁCS heart failure", true),
            new Vector("kiegészítő", "név raggal", "Kovácsné heart failure", true),
            new Vector("kiegészítő", "életkor raggal", "70 évesnél heart failure", true),
            new Vector("kiegészítő", "életkor angolul, 'aged'", "heart failure aged 70", true),
            new Vector("kiegészítő", "telefonszám tagolva", "heart failure +36 30 123 4567", true),
            new Vector("kiegészítő", "születési dátum, perjellel", "heart failure 14/03/1956", true),
            new Vector("kiegészítő", "születési dátum, szóközzel", "heart failure 1956. 03. 14.", true),
            new Vector("kiegészítő", "születési év önmagában", "heart failure 1956", true),
            new Vector("kiegészítő", "TAJ pontokkal", "heart failure 123.456.788", true));

    private boolean blocked(String query) {
        try {
            gate.check(query, patient);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    @Test
    void measuredVectors() {
        List<String> failures = new ArrayList<>();
        StringBuilder out = new StringBuilder("\n| Csoport | Vektor | Kifejezés | Elvárt | Kapu | Helyes |\n|---|---|---|---|---|---|\n");
        int[] main = report(MAIN, out, failures);
        int[] extra = report(EXTRA, out, new ArrayList<>());
        out.append(String.format("%nFő készlet: %d/%d helyes (tiltandó: %d/%d elutasítva; átengedendő: %d/%d átengedve; "
                        + "hamis negatív %d, hamis pozitív %d)%nKiegészítő készlet: %d/%d elutasítva%n",
                main[0], MAIN.size(), main[1], main[2], main[3], main[4], main[2] - main[1], main[4] - main[3],
                extra[1], extra[2]));
        System.out.println(out);
        assertTrue(failures.isEmpty(), "Hibás kapu-döntés: " + failures);
    }

    // [helyes, tiltva a tiltandókból, tiltandók száma, átengedve az átengedendőkből, átengedendők száma]
    private int[] report(List<Vector> vectors, StringBuilder out, List<String> failures) {
        int[] c = new int[5];
        for (Vector v : vectors) {
            boolean b = blocked(v.query());
            boolean ok = b == v.shouldBlock();
            if (ok) c[0]++;
            if (v.shouldBlock()) { c[2]++; if (b) c[1]++; } else { c[4]++; if (!b) c[3]++; }
            if (!ok) failures.add(v.label());
            out.append(String.format("| %s | %s | `%s` | %s | %s | %s |%n", v.group(), v.label(), v.query(),
                    v.shouldBlock() ? "tilt" : "átenged", b ? "tilt" : "átenged", ok ? "igen" : "NEM"));
        }
        return c;
    }
}
