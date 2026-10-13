package bme.mediai.recordingsummary.exception;

public class RecordingSummaryNotFoundException extends RuntimeException {

    public RecordingSummaryNotFoundException(Long id) {
        super("Nem található felvétel-összefoglaló ezzel az azonosítóval: " + id);
    }
}
