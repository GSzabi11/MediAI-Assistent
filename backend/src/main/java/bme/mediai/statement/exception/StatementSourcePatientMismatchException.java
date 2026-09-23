package bme.mediai.statement.exception;

public class StatementSourcePatientMismatchException extends RuntimeException {

    // sourceDescription: pl. "felvétel 3" vagy "dokumentum 7"
    public StatementSourcePatientMismatchException(String sourceDescription, Long sourcePatientId,
            Long statementPatientId) {
        super("A forrás (" + sourceDescription + ") a(z) " + sourcePatientId
                + " beteghez tartozik, az állítás viszont a(z) " + statementPatientId + " beteghez");
    }
}
