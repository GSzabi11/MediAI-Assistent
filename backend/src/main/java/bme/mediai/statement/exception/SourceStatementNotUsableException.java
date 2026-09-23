package bme.mediai.statement.exception;

// Egy állítás nem használható másik rekord (gyógyszer, allergia) forrásaként:
// más beteghez tartozik, vagy nincs orvosilag jóváhagyva.
public class SourceStatementNotUsableException extends RuntimeException {

    public SourceStatementNotUsableException(String message) {
        super(message);
    }
}
