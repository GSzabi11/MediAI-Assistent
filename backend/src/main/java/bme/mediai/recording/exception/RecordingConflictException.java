package bme.mediai.recording.exception;

// 409: a feldolgozás eredménye már megérkezett, vagy a felvétel nem feldolgozás alatt áll
public class RecordingConflictException extends RuntimeException {

    public RecordingConflictException(String message) {
        super(message);
    }
}
