package bme.mediai.recording.persistence;

public enum RecordingStatus {
    RECORDING,
    PROCESSING,
    DONE,
    FAILED;

    public boolean canTransitionTo(RecordingStatus next) {
        return switch (this) {
            case RECORDING -> next == PROCESSING || next == FAILED;
            case PROCESSING -> next == DONE || next == FAILED;
            case DONE, FAILED -> false;
        };
    }
}
