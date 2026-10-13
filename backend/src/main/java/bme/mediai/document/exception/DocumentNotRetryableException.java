package bme.mediai.document.exception;

// Újrafeldolgozás csak sikertelen, automatikusan feldolgozott (PDF), kinyert szöveg nélküli
// dokumentumra indítható: kézzel rögzített szövegre már hivatkozhatnak állítások.
public class DocumentNotRetryableException extends RuntimeException {

    public DocumentNotRetryableException(Long id) {
        super("A(z) " + id + " dokumentum feldolgozása nem indítható újra: csak sikertelen, kinyert szöveg "
                + "nélküli PDF dolgozható fel újra");
    }
}
