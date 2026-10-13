package bme.mediai.recording.api;

import java.util.List;

import bme.mediai.utterance.persistence.Speaker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// Python → Spring: POST /api/recordings/{id}/processing-result. FAILED-nél csak az error.
public record RecordingResultRequest(

    @NotNull @Pattern(regexp = "^(DONE|FAILED)$", message = "The status must be DONE or FAILED")
    String status,

    @Size(max = 2000)
    String error,

    @Size(max = 128)
    String asrModel,

    // null: az átnézés nem futott (pl. az Ollama nem érhető el) — figyelmeztetés jelzi
    @Size(max = 128)
    String reviewModel,

    @Size(max = 5000)
    List<@Valid Turn> utterances,

    @Size(max = 50)
    List<@NotBlank @Size(max = 500) String> warnings,

    // az AI kulcsszó-javaslatai (a Python ellenőrizte, hogy elhangzottak), és a modell
    @Size(max = 50)
    List<@NotBlank @Size(max = 200) String> keywords,

    @Size(max = 128)
    String keywordModel
) {
    public record Turn(
        @NotNull @PositiveOrZero Integer turnId,
        @NotNull Speaker speaker,
        @DecimalMin("-1.0") @DecimalMax("1.0") Float similarity,
        @NotNull @PositiveOrZero Integer startMs,
        @NotNull @PositiveOrZero Integer endMs,
        @NotBlank String text,
        // az ASR eredetije, ha a modell javította (különben null)
        String asrText,
        Speaker asrSpeaker,
        @Size(max = 500) String correctionReason,
        // az AI megjegyzése módosítás nélkül (pl. ellenőrizendő érték)
        @Size(max = 500) String reviewNote
    ) {
    }
}
