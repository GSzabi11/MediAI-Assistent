package bme.mediai.recordingfinding.exception;

public class RecordingFindingNotFoundException extends RuntimeException {

    public RecordingFindingNotFoundException(String what, Long id) {
        super("Nem található " + what + " ezzel az azonosítóval: " + id);
    }
}
