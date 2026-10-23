package bme.mediai.research.service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import bme.mediai.patient.persistence.Patient;

// A hálózatra kimenő keresőkifejezés kapuja (docs/literature-research.md). Determinisztikus: ha a
// kifejezésben a beteg azonosító adata áll (név, anyja neve, TAJ, születési dátum, lakcím), vagy
// bármilyen hosszú számsor / pontos életkor, a keresés NEM indul (400). Ez az utolsó réteg: a
// kifejezést az orvos már látta és jóváhagyta, a Python a modell javaslatából a számsorokat levágja.
@Component
public class OutboundQueryGate {

    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{5,}");
    private static final Pattern AGE = Pattern.compile("\\b\\d{1,3}\\s*(?:eves|ev|years?[ -]old|yo|y/o)\\b");
    private static final Pattern WORD_SPLIT = Pattern.compile("[^a-z0-9]+");
    private static final int MIN_TOKEN = 3;

    public void check(String query, Patient patient) {
        String q = normalize(query);
        List<String> problems = new ArrayList<>();
        if (LONG_DIGITS.matcher(q).find()) {
            problems.add("hosszú számsor (TAJ, azonosító?)");
        }
        if (AGE.matcher(q).find()) {
            problems.add("pontos életkor");
        }
        String digits = q.replaceAll("\\D", "");
        if (patient.getTaj() != null && digits.contains(patient.getTaj())) {
            problems.add("a beteg TAJ-száma");
        }
        if (containsToken(q, patient.getName())) {
            problems.add("a beteg neve");
        }
        if (containsToken(q, patient.getMotherName())) {
            problems.add("az anyja neve");
        }
        if (containsToken(q, patient.getAddress())) {
            problems.add("a lakcím egy része");
        }
        if (patient.getBirthDate() != null && containsDate(q, digits, patient.getBirthDate())) {
            problems.add("a születési dátum");
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("A keresőkifejezés betegazonosító adatot tartalmaz ("
                    + String.join(", ", problems) + ") — a hálózatra csak orvosi fogalom mehet ki, javítsd");
        }
    }

    // ékezet nélkül, kisbetűvel: "Kovács" = "kovacs"
    static String normalize(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static boolean containsToken(String normalizedQuery, String field) {
        if (field == null || field.isBlank()) {
            return false;
        }
        List<String> words = Arrays.asList(WORD_SPLIT.split(normalizedQuery));
        return Arrays.stream(WORD_SPLIT.split(normalize(field)))
                .filter(t -> t.length() >= MIN_TOKEN && !t.chars().allMatch(Character::isDigit))
                .anyMatch(words::contains);
    }

    private static boolean containsDate(String normalizedQuery, String digits, LocalDate d) {
        String compact = d.format(DateTimeFormatter.BASIC_ISO_DATE);
        return digits.contains(compact)
                || normalizedQuery.contains(d.toString())
                || normalizedQuery.contains(d.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")))
                || normalizedQuery.contains(d.format(DateTimeFormatter.ofPattern("yyyy. MM. dd")));
    }
}
