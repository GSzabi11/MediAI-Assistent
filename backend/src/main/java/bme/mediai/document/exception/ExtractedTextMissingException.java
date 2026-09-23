package bme.mediai.document.exception;

public class ExtractedTextMissingException extends RuntimeException {

    public ExtractedTextMissingException(Long id) {
        super("A(z) " + id + " dokumentumnak még nincs kinyert szövege, karakterpozícióval nem hivatkozható");
    }
}
