package bme.mediai.recording.exception;

public class RecordingNotFoundException extends RuntimeException {

    public RecordingNotFoundException(Long id) {
        super("Nem található felvétel ezzel az azonosítóval: " + id);
    }
}
