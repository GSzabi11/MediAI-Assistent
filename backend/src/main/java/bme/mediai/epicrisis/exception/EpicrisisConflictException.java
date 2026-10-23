package bme.mediai.epicrisis.exception;

// Már készül epikrízis az ellátáshoz, az eredmény már megérkezett, vagy nem szerkeszthető / exportálható
public class EpicrisisConflictException extends RuntimeException {

    public EpicrisisConflictException(String message) {
        super(message);
    }
}
