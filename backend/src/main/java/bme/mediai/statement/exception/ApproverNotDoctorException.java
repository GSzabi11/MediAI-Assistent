package bme.mediai.statement.exception;

public class ApproverNotDoctorException extends RuntimeException {

    public ApproverNotDoctorException(Long userId) {
        super("Állítást csak orvos (DOCTOR) hagyhat jóvá; a(z) " + userId + " felhasználó nem orvos");
    }
}
