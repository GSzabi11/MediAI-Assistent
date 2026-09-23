package bme.mediai.document.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record ChunkBatchRequest(

    @NotBlank(message = "The embeddingModel must not be blank")
    @Size(max = 128)
    String embeddingModel,

    @NotEmpty(message = "The chunks list must not be empty")
    List<@Valid ChunkRequest> chunks
)
{}
