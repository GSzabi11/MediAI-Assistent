package bme.mediai.document.exception;

public class ExtractedTextAlreadySetException extends RuntimeException {

    public ExtractedTextAlreadySetException(Long id) {
        super("A(z) " + id + " dokumentum kinyert szövege már be van állítva, nem módosítható");
    }
}
