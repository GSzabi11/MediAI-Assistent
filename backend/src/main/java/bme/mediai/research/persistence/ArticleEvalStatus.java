package bme.mediai.research.persistence;

// NONE → PENDING → DONE | FAILED; FAILED (és a beragadt PENDING) újrakérhető
public enum ArticleEvalStatus {
    NONE,
    PENDING,
    DONE,
    FAILED
}
