package bme.mediai.lab.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

// Egy labor-panel egyben: minden sor ugyanahhoz a beteghez (és dokumentumhoz) tartozik.
public record LabBatchRequest(

    @NotNull(message = "The patientId must not be null")
    Long patientId,

    Long documentId,

    @NotEmpty(message = "The results list must not be empty")
    List<@Valid LabResultRequest> results
)
{}
