package bme.mediai.chat.persistence;

// PROCESSING → DONE | FAILED (a Python callback dönt, egyszer)
public enum ChatStatus {
    PROCESSING,
    DONE,
    FAILED
}
