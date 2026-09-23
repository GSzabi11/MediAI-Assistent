package bme.mediai.medication.exception;

public class MedicationAlreadyDiscontinuedException extends RuntimeException {

    public MedicationAlreadyDiscontinuedException(Long id) {
        super("A(z) " + id + " gyógyszer már le van állítva; újrakezdéshez új sort kell felvenni");
    }
}
