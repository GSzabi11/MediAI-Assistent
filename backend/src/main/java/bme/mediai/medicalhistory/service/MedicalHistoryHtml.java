package bme.mediai.medicalhistory.service;

import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Safelist;

import bme.mediai.medicalhistory.persistence.MedicalHistory;

// A kórtörténet szerkeszthető dokumentuma (HTML) — EGY helyen: a szerkesztő ebből indul, az export
// ezt (vagy az orvos szerkesztését) teszi a DOCX-be. Szerkezete a minta-DOCX-é: Összefoglaló,
// Kórtörténeti idővonal, Diagnózisok, Aktuális gyógyszerelés, Javaslatok.
public final class MedicalHistoryHtml {

    // a szerkesztő eszköztára ennyit tud (félkövér, dőlt, aláhúzott, felsorolás, számozott lista) +
    // a generált szerkezet (címsor, bekezdés, az idővonal behúzott leírása: class="tl-desc")
    private static final Safelist ALLOWED = new Safelist()
            .addTags("p", "br", "div", "b", "strong", "i", "em", "u", "ul", "ol", "li", "h2", "h3")
            .addAttributes("p", "class");

    private static final Document.OutputSettings OUTPUT = new Document.OutputSettings().prettyPrint(false);

    private MedicalHistoryHtml() {
    }

    // Az orvos HTML-je: minden más elem és attribútum (script, style, on*, link, kép) kimarad
    public static String sanitize(String html) {
        return Jsoup.clean(html == null ? "" : html, "", ALLOWED, OUTPUT).strip();
    }

    // Szöveg nélküli (csak üres elemekből álló) szerkesztés nem menthető
    public static boolean isBlank(String html) {
        return Jsoup.parse(html).text().isBlank();
    }

    // A megjelenített / exportált szöveg: az orvos szerkesztése, ha van, különben az AI eredetije
    public static String effective(MedicalHistory h) {
        return h.getEditedHtml() != null ? h.getEditedHtml() : render(h);
    }

    // Az AI eredetije a strukturált mezőkből. Az ellenőrizendő idővonal-elem jelölése a szövegbe kerül
    // ("[Ellenőrizendő: …]") — exportba is, amíg az orvos ki nem törli.
    public static String render(MedicalHistory h) {
        if (h.getSummary() == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        out.append("<h2>Összefoglaló</h2>");
        for (String paragraph : h.getSummary().strip().split("\\n\\s*\\n")) {
            out.append("<p>").append(escape(paragraph.strip()).replace("\n", "<br>")).append("</p>");
        }

        // a legfrissebb felül (a tárolt sorrend időrendi — a modell is így kapja)
        List<MedicalHistory.TimelineItem> timeline = h.getTimeline().reversed();
        if (!timeline.isEmpty()) {
            out.append("<h2>Kórtörténeti idővonal</h2>");
            for (MedicalHistory.TimelineItem t : timeline) {
                out.append("<p><b>").append(escape(t.date())).append("</b> — <b>").append(escape(t.title()))
                        .append("</b>");
                if (t.place() != null) {
                    out.append(" (").append(escape(t.place())).append(")");
                }
                out.append("</p>");
                StringBuilder desc = new StringBuilder();
                if (t.flag() != null) {
                    desc.append("<i>[Ellenőrizendő: ").append(escape(t.flag())).append("]</i> ");
                }
                if (t.text() != null) {
                    desc.append(escape(t.text()));
                }
                if (!desc.isEmpty()) {
                    out.append("<p class=\"tl-desc\">").append(desc).append("</p>");
                }
            }
        }

        if (!h.getDiagnoses().isEmpty()) {
            out.append("<h2>Diagnózisok</h2><ul>");
            for (MedicalHistory.Diagnosis d : h.getDiagnoses()) {
                out.append("<li>");
                if (d.bnoCode() != null) {
                    out.append("<b>").append(escape(d.bnoCode())).append("</b> – ");
                }
                out.append(escape(d.name()));
                if (d.date() != null) {
                    out.append(" <i>(").append(escape(d.date())).append(")</i>");
                }
                out.append("</li>");
            }
            out.append("</ul>");
        }

        if (!h.getMedications().isEmpty()) {
            out.append("<h2>Aktuális gyógyszerelés</h2><ul>");
            for (MedicalHistory.Medication m : h.getMedications()) {
                out.append("<li>").append(escape(m.name()));
                if (m.dose() != null) {
                    out.append(" ").append(escape(m.dose()));
                }
                if (m.route() != null) {
                    out.append(", ").append(escape(m.route()));
                }
                out.append("</li>");
            }
            out.append("</ul>");
        }

        if (!h.getRecommendations().isEmpty()) {
            out.append("<h2>Javaslatok</h2><ul>");
            h.getRecommendations().forEach(r -> out.append("<li>").append(escape(r)).append("</li>"));
            out.append("</ul>");
        }
        return out.toString();
    }

    static String escape(String s) {
        return Entities.escape(s);
    }
}
