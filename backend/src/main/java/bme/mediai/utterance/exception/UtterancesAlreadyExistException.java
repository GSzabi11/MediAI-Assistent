package bme.mediai.utterance.exception;

public class UtterancesAlreadyExistException extends RuntimeException {

    public UtterancesAlreadyExistException(Long recordingId) {
        super("A(z) " + recordingId + " felvételhez már rögzítették a megszólalásokat");
    }
}
