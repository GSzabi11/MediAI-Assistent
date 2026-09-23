package bme.mediai.recording.api;

import bme.mediai.recording.persistence.RecordingStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record RecordingStatusRequest(

    @NotNull(message = "The status must not be null")
    RecordingStatus status,

    @PositiveOrZero
    Integer durationSec
)
{}
