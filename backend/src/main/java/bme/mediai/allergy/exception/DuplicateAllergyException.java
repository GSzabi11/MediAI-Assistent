package bme.mediai.allergy.exception;

public class DuplicateAllergyException extends RuntimeException {

    public DuplicateAllergyException(Long patientId, String substance) {
        super("A(z) " + patientId + " betegnél már rögzítve van allergia erre az anyagra: " + substance);
    }
}
