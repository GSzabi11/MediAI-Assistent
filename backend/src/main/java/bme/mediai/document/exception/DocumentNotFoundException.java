package bme.mediai.document.exception;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(Long id) {
        super("Nem található dokumentum ezzel az azonosítóval: " + id);
    }
}
