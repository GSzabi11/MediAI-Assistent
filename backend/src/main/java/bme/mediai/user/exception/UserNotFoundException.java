package bme.mediai.user.exception;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long id) {
        super("Nem található felhasználó ezzel az azonosítóval: " + id);
    }

    public UserNotFoundException(String field, String value) {
        super("Nem található felhasználó ezzel: " + field + " = " + value);
    }
}
