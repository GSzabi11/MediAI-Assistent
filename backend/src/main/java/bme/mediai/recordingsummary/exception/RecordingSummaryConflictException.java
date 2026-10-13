package bme.mediai.recordingsummary.exception;

// 409: már készül összefoglaló, a felvétel még nincs kész, vagy már megérkezett az eredmény
public class RecordingSummaryConflictException extends RuntimeException {

    public RecordingSummaryConflictException(String message) {
        super(message);
    }
}
