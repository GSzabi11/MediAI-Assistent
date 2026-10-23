package bme.mediai.epicrisis.service;

import java.time.LocalDate;
import java.time.ZoneId;

import org.jsoup.nodes.Entities;

import bme.mediai.epicrisis.persistence.Epicrisis;
import bme.mediai.medicalcare.persistence.MedicalCare;
import bme.mediai.medicalhistory.service.MedicalHistoryHtml;
import bme.mediai.patient.persistence.Patient;

// Az epikrízis a felhasználó sablonja szerint — EGY helyen: a felület, a szerkesztő és a DOCX is ezt használja.
//   Beteg: Kovács István (sz.: 1958-03-14)      ┐ fejléc: az adatbázisból (determinisztikus),
//   TAJ: 123-456-789                            │ NEM szerkeszthető, nem a modelltől jön
//   Dátum: 2026. 10. 15.                        ┘
//   DIAGNÓZISOK: / KRITIKUS MEGÁLLAPÍTÁSOK: / JAVASLATOK:   ← szerkeszthető törzs (AI-eredeti vagy az orvosé)
public final class EpicrisisHtml {

    private static final ZoneId CLINICAL_ZONE = ZoneId.of("Europe/Budapest");

    private EpicrisisHtml() {
    }

    public record Header(String patient, String taj, String date) {
    }

    // Dátum: a lezárt ellátás zárásának napja, a nyitotté a kezdetéé
    public static Header header(Patient patient, MedicalCare care) {
        String name = patient.getName() == null || patient.getName().isBlank() ? "Azonosítatlan beteg"
                : patient.getName().strip();
        String line = patient.getBirthDate() == null ? name : name + " (sz.: " + patient.getBirthDate() + ")";
        LocalDate date = (care.getEndedAt() != null ? care.getEndedAt() : care.getStartedAt())
                .atZoneSameInstant(CLINICAL_ZONE).toLocalDate();
        return new Header(line, taj(patient.getTaj()),
                String.format("%d. %02d. %02d.", date.getYear(), date.getMonthValue(), date.getDayOfMonth()));
    }

    public static String renderHeader(Header h) {
        return "<p>Beteg: " + escape(h.patient()) + "</p><p>TAJ: " + escape(h.taj()) + "</p><p>Dátum: "
                + escape(h.date()) + "</p>";
    }

    // A megjelenített / exportált törzs: az orvos szerkesztése, ha van, különben az AI eredetije
    public static String effective(Epicrisis e) {
        return e.getEditedHtml() != null ? e.getEditedHtml() : render(e);
    }

    // Az AI eredetije a strukturált mezőkből. Az ellenőrizendő megállapítás jelölése a szövegbe kerül
    // ("[Ellenőrizendő: …]") — exportba is, amíg az orvos ki nem törli.
    public static String render(Epicrisis e) {
        if (e.getFindings().isEmpty() && e.getDiagnoses().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h2>DIAGNÓZISOK:</h2>");
        if (e.getDiagnoses().isEmpty()) {
            out.append("<p class=\"tl-desc\"><i>A kiválasztott leletekben és az átiratban nincs diagnózis.</i></p>");
        }
        for (Epicrisis.Diagnosis d : e.getDiagnoses()) {
            out.append("<p class=\"tl-desc\">");
            if (d.bnoCode() != null) {
                out.append(escape(d.bnoCode())).append(" – ");
            }
            out.append(escape(d.name())).append("</p>");
        }

        out.append("<h2>KRITIKUS MEGÁLLAPÍTÁSOK:</h2><ul>");
        for (Epicrisis.Finding f : e.getFindings()) {
            out.append("<li>");
            if (f.flag() != null) {
                out.append("<i>[Ellenőrizendő: ").append(escape(f.flag())).append("]</i> ");
            }
            out.append(escape(f.text())).append("</li>");
        }
        out.append("</ul><h2>JAVASLATOK:</h2>");
        if (e.getRecommendations().isEmpty()) {
            out.append("<p class=\"tl-desc\"><i>Nincs javaslat.</i></p>");
        } else {
            out.append("<ul>");
            e.getRecommendations().forEach(r -> out.append("<li>").append(escape(r)).append("</li>"));
            out.append("</ul>");
        }
        return out.toString();
    }

    // A szerkesztő HTML-jének szűrése — ugyanaz az allowlist, mint a kórtörténetnél
    public static String sanitize(String html) {
        return MedicalHistoryHtml.sanitize(html);
    }

    public static boolean isBlank(String html) {
        return MedicalHistoryHtml.isBlank(html);
    }

    static String escape(String s) {
        return Entities.escape(s);
    }

    // 123456789 → 123-456-789 (azonosítatlan / külföldi betegnél "–")
    private static String taj(String taj) {
        if (taj == null || taj.length() != 9) {
            return "–";
        }
        return taj.substring(0, 3) + "-" + taj.substring(3, 6) + "-" + taj.substring(6);
    }
}
