package bme.mediai.user.exception;

public class EmailConflictException extends RuntimeException {

    public EmailConflictException(String email) {
        super("Ezzel az email-címmel már létezik felhasználó: " + email);
    }
}
