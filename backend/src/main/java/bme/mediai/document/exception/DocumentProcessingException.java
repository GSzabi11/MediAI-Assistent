package bme.mediai.document.exception;

public class DocumentProcessingException extends RuntimeException {

    public DocumentProcessingException(Long id) {
        super("A(z) " + id + " dokumentum automatikus feldolgozás alatt áll, most nem módosítható");
    }
}
