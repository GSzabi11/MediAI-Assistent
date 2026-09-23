package bme.mediai.medicalcare.exception;

public class MedicalCarePatientMismatchException extends RuntimeException {

    public MedicalCarePatientMismatchException(Long medicalCareId, Long medicalCarePatientId, Long requestedPatientId) {
        super("A(z) " + medicalCareId + " ellátás a(z) " + medicalCarePatientId
                + " beteghez tartozik, nem a(z) " + requestedPatientId + " beteghez");
    }
}
