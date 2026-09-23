package bme.mediai.statement.api;

import java.time.OffsetDateTime;

import bme.mediai.statement.persistence.StatementProducer;
import bme.mediai.statement.persistence.StatementSource;
import bme.mediai.statement.persistence.StatementType;
import bme.mediai.statement.persistence.Severity;
import bme.mediai.statement.persistence.Uncertainty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StatementCreateRequest(

    @NotNull(message = "The patientId must not be null")
    Long patientId,

    @NotNull(message = "The type must be RETRIEVED_FACT or MODEL_INFERENCE")
    StatementType type,

    @NotBlank(message = "The text must not be blank")
    String text,

    @Size(max = 128)
    String label,

    @NotNull(message = "The severity must be INFO, WARNING or CRITICAL")
    Severity severity,

    Uncertainty uncertainty,

    @NotNull(message = "The source must not be null")
    StatementSource source,

    @NotNull(message = "The producer must not be null")
    StatementProducer producer,

    // Ha hiányzik, AUDIO forrásnál a felvétel ideje; DOCUMENT/IMAGE forrásnál kötelező.
    OffsetDateTime clinicalTime
)
{}
