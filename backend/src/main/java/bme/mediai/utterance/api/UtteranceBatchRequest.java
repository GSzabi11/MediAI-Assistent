package bme.mediai.utterance.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record UtteranceBatchRequest(

    @NotEmpty(message = "The utterances list must not be empty")
    List<@Valid UtteranceRequest> utterances
)
{}
