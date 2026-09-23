package bme.mediai.voiceprofile.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record VoiceProfileCreateRequest(

    @NotNull(message = "The userId must not be null")
    Long userId,

    @NotBlank(message = "The wavPath must not be blank")
    String wavPath,

    @NotBlank(message = "The embeddingModel must not be blank")
    @Size(max = 128)
    String embeddingModel,

    @DecimalMin(value = "0.0", message = "The threshold must be between 0 and 1")
    @DecimalMax(value = "1.0", message = "The threshold must be between 0 and 1")
    @Digits(integer = 1, fraction = 3, message = "The threshold must have at most 3 decimals")
    BigDecimal threshold,

    @PositiveOrZero
    @Digits(integer = 4, fraction = 2)
    BigDecimal netSpeechSec,

    @Size(max = 255)
    String microphone,

    @NotNull(message = "The recordedAt must not be null")
    OffsetDateTime recordedAt
)
{}
