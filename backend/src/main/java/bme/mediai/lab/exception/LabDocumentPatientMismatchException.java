package bme.mediai.lab.exception;

public class LabDocumentPatientMismatchException extends RuntimeException {

    public LabDocumentPatientMismatchException(Long documentId, Long documentPatientId, Long requestedPatientId) {
        super("A(z) " + documentId + " dokumentum a(z) " + documentPatientId
                + " beteghez tartozik, nem a(z) " + requestedPatientId + " beteghez");
    }
}
