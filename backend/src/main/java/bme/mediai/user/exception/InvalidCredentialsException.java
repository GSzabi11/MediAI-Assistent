package bme.mediai.user.exception;

// Szándékosan nem mondja meg, a név vagy a jelszó volt-e rossz.
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Hibás felhasználónév vagy jelszó");
    }
}
