package bme.mediai.document.exception;

public class DuplicateDocumentException extends RuntimeException {

    public DuplicateDocumentException(Long patientId, String sha256) {
        super("Ez a fájl (sha256: " + sha256 + ") már fel van töltve a(z) " + patientId + " beteghez");
    }
}
