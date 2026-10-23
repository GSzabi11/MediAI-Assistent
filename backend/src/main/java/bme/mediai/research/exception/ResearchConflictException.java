package bme.mediai.research.exception;

// Már készül összefoglaló, az eredmény már megérkezett, vagy a művelet az állapotban nem végezhető (409)
public class ResearchConflictException extends RuntimeException {

    public ResearchConflictException(String message) {
        super(message);
    }
}
