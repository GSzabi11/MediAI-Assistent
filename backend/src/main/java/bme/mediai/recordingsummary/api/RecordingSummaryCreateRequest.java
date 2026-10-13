package bme.mediai.recordingsummary.api;

import jakarta.validation.constraints.NotNull;

// Újragenerálás gombbal (a userId a kérésből jön — még nincs bejelentkezés)
public record RecordingSummaryCreateRequest(

    @NotNull(message = "The userId must not be null")
    Long userId
) {
}
