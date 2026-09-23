package bme.mediai.medication.exception;

public class MedicationNotFoundException extends RuntimeException {

    public MedicationNotFoundException(Long id) {
        super("Nem található gyógyszer ezzel az azonosítóval: " + id);
    }
}
