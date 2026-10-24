package bme.mediai.recordingfinding.api;

import java.util.List;

import bme.mediai.recordingfinding.persistence.RecordingFinding;

// Egy érvényes csoportelem az orvos szerkesztett állapotában (szöveg, BNO) — az összefoglaló és az epikrízis
// bemenete (Spring → Python). turnIds: a forrás-megszólalások (az orvos kézi eleménél üres lehet).
public record FindingFact(String category, String text, String bnoCode, List<Integer> turnIds) {

    public static FindingFact from(RecordingFinding f) {
        return new FindingFact(f.getCategory().name(), f.getEffectiveText(), f.getEffectiveBnoCode(), f.getTurnIds());
    }
}
