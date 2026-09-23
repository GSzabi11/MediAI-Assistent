package bme.mediai.recording.api;


import bme.mediai.recording.persistence.RecordingMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RecordingCreateRequest(

    @NotNull(message = "The patientId must not be null")
    Long patientId,

    @NotNull(message = "The userId must not be null")
    Long userId,

    Long encounterId,

    @NotNull(message = "The mode must be SBO or ECHO")
    RecordingMode mode,

    @NotBlank(message = "The audioPath must not be blank")
    String audioPath,

    @NotBlank(message = "The asrModel must not be blank")
    @Size(max = 128)
    String asrModel

)
{}
