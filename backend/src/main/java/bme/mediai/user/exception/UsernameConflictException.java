package bme.mediai.user.exception;

public class UsernameConflictException extends RuntimeException {

    public UsernameConflictException(String username) {
        super("Ezzel a felhasználónévvel már létezik felhasználó: " + username);
    }
}
