package bme.mediai.statement.exception;

public class StatementSourcePatientMismatchException extends RuntimeException {

    public StatementSourcePatientMismatchException(Long recordingId, Long recordingPatientId, Long statementPatientId) {
        super("A forrás-felvétel (" + recordingId + ") a(z) " + recordingPatientId
                + " beteghez tartozik, az állítás viszont a(z) " + statementPatientId + " beteghez");
    }
}
