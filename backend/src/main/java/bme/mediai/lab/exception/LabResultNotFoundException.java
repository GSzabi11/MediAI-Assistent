package bme.mediai.lab.exception;

public class LabResultNotFoundException extends RuntimeException {

    public LabResultNotFoundException(Long id) {
        super("Nem található laboreredmény ezzel az azonosítóval: " + id);
    }
}
