package bme.mediai.patient.exception;

public class TajConflictException extends RuntimeException {

    public TajConflictException(String taj) {
        super("Ezzel a TAJ-számmal már létezik beteg: " + taj);
    }
}