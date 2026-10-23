package bme.mediai.medicalhistory.exception;

public class MedicalHistoryNotFoundException extends RuntimeException {

    public MedicalHistoryNotFoundException(Long id) {
        super("Nem található kórtörténeti összefoglaló ezzel az azonosítóval: " + id);
    }
}
