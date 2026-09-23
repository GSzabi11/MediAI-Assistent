package bme.mediai.statement.api;

import bme.mediai.statement.persistence.ApprovalStatus;
import jakarta.validation.constraints.NotNull;

public record ApprovalCreateRequest(

    @NotNull(message = "The userId must not be null")
    Long userId,

    @NotNull(message = "The status must be ACCEPTED, REJECTED or MODIFIED")
    ApprovalStatus status,

    String modifiedText
)
{}
