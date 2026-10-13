package bme.mediai.keyword.api;

import bme.mediai.keyword.persistence.KeywordStatus;
import jakarta.validation.constraints.NotNull;

// Az orvos döntése egy AI-javaslatról: ACCEPTED vagy REJECTED
public record KeywordDecisionRequest(

    @NotNull(message = "The userId must not be null")
    Long userId,

    @NotNull(message = "The status must be ACCEPTED or REJECTED")
    KeywordStatus status
) {
}
