package bme.mediai.processing.exception;

public class InvalidCallbackTokenException extends RuntimeException {

    public InvalidCallbackTokenException() {
        super("Érvénytelen vagy hiányzó belső szolgáltatás-token");
    }
}
