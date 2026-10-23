package bme.mediai.research.persistence;

// Az AI-rangsor állapota (V32): NONE → PENDING → DONE | FAILED; a DONE / FAILED (és a beragadt PENDING)
// újrakérhető
public enum RankingStatus {
    NONE,
    PENDING,
    DONE,
    FAILED
}
