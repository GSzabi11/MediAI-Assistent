package bme.mediai.patient.exception;

public class PatientNotFoundException extends RuntimeException {

    public PatientNotFoundException(Long id) {
        super("Nem található beteg ezzel az azonosítóval: " + id);
    }
}