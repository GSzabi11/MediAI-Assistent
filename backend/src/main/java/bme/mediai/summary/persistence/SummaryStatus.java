package bme.mediai.summary.persistence;

// PROCESSING → DONE | FAILED (a Python callback dönt, egyszer)
public enum SummaryStatus {
    PROCESSING,
    DONE,
    FAILED
}
