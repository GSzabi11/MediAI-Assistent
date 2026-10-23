package bme.mediai.medicalhistory.exception;

// Már készül kórtörténet a betegnek, az eredmény már megérkezett, vagy nem szerkeszthető
public class MedicalHistoryConflictException extends RuntimeException {

    public MedicalHistoryConflictException(String message) {
        super(message);
    }
}
