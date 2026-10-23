package bme.mediai.recordingfinding.persistence;

// Panaszok, diagnózisok, beutalók, vizsgálati megállapítások, javasolt gyógyszerek, javaslatok / kontroll
public enum FindingCategory {
    COMPLAINT,
    DIAGNOSIS,
    REFERRAL,
    EXAM_FINDING,
    MEDICATION,
    RECOMMENDATION;

    /** BNO-kód csak panaszhoz és diagnózishoz tartozhat (DB CHECK is). */
    public boolean allowsBnoCode() {
        return this == COMPLAINT || this == DIAGNOSIS;
    }
}
