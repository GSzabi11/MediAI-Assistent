package bme.mediai.summary.exception;

// Már készül összefoglaló a betegnek, vagy az eredmény már megérkezett
public class SummaryConflictException extends RuntimeException {

    public SummaryConflictException(String message) {
        super(message);
    }
}
