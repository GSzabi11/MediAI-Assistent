package bme.mediai.medicalcare.exception;

public class MedicalCareClosedException extends RuntimeException {

    public MedicalCareClosedException(Long id) {
        super("A(z) " + id + " ellátás már le van zárva");
    }
}
