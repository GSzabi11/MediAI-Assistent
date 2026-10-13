package bme.mediai.voiceprofile.api;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// Python → Spring: POST /api/voice-profiles/{id}/enrollment-result. FAILED-nél csak az error.
public record VoiceEnrollmentResultRequest(

    @NotNull @Pattern(regexp = "^(READY|FAILED)$", message = "The status must be READY or FAILED")
    String status,

    @Size(max = 2000)
    String error,

    List<Float> embedding,

    @Size(max = 128)
    String embeddingModel,

    @PositiveOrZero
    @Digits(integer = 4, fraction = 2)
    BigDecimal netSpeechSec,

    @PositiveOrZero
    Integer sentencesUsed,

    @Size(max = 20)
    List<@NotBlank @Size(max = 500) String> warnings
) {
}
