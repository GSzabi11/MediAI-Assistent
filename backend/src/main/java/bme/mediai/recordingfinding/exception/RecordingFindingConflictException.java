package bme.mediai.recordingfinding.exception;

// 409: már fut kinyerés, a felvétel még nincs kész, nem orvos felhasználó, már megérkezett eredmény,
// törölt elem szerkesztése
public class RecordingFindingConflictException extends RuntimeException {

    public RecordingFindingConflictException(String message) {
        super(message);
    }
}
