package bme.mediai.document.persistence;

// PDF-nél: PROCESSING → PROCESSED | FAILED (a Python callback dönt, egyszer).
// Kézzel, típussal feltöltött (nem PDF) dokumentum rögtön PROCESSED.
public enum DocumentStatus {
    PROCESSING,
    PROCESSED,
    FAILED
}
