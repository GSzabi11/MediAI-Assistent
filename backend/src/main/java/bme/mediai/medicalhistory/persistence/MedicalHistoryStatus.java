package bme.mediai.medicalhistory.persistence;

// PROCESSING → DONE | FAILED (a Python callback dönt, egyszer)
public enum MedicalHistoryStatus {
    PROCESSING,
    DONE,
    FAILED
}
