package bme.mediai.statement.exception;

public class StatementNotFoundException extends RuntimeException {

    public StatementNotFoundException(Long id) {
        super("Nem található állítás ezzel az azonosítóval: " + id);
    }
}
