package bme.mediai.utterance.api;

import bme.mediai.utterance.persistence.Speaker;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UtteranceRequest(

    @NotNull(message = "The turnId must not be null")
    @PositiveOrZero
    Integer turnId,

    @NotNull(message = "The speaker must be DOCTOR, PATIENT or OTHER")
    Speaker speaker,

    @DecimalMin("-1.0")
    @DecimalMax("1.0")
    Float similarity,

    @NotNull(message = "The startMs must not be null")
    @PositiveOrZero
    Integer startMs,

    @NotNull(message = "The endMs must not be null")
    @PositiveOrZero
    Integer endMs,

    @NotBlank(message = "The text must not be blank")
    String text,

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    Float asrConfidence
)
{}
