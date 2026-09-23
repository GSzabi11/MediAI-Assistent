package bme.mediai.document.api;

import jakarta.validation.constraints.NotBlank;

public record ExtractedTextRequest(

    @NotBlank(message = "The text must not be blank")
    String text
)
{}
