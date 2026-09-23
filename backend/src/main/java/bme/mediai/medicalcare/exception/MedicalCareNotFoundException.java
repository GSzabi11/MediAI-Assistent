package bme.mediai.medicalcare.exception;

public class MedicalCareNotFoundException extends RuntimeException {

    public MedicalCareNotFoundException(Long id) {
        super("Nem található ellátás ezzel az azonosítóval: " + id);
    }
}
