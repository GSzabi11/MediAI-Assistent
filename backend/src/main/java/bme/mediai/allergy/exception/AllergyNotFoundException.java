package bme.mediai.allergy.exception;

public class AllergyNotFoundException extends RuntimeException {

    public AllergyNotFoundException(Long id) {
        super("Nem található allergia ezzel az azonosítóval: " + id);
    }
}
