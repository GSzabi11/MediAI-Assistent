package bme.mediai.epicrisis.exception;

public class EpicrisisNotFoundException extends RuntimeException {

    public EpicrisisNotFoundException(Long id) {
        super("Nem található epikrízis ezzel az azonosítóval: " + id);
    }
}
