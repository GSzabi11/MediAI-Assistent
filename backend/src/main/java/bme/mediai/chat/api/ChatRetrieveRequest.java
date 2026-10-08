package bme.mediai.chat.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Python → Spring: POST /api/chat-messages/{id}/retrieve — a kérdés vektora (ugyanazzal a
// modellel, mint a document_chunk vektorai; más modell vektorai nem összehasonlíthatók)
public record ChatRetrieveRequest(

    @NotNull
    float[] embedding,

    @NotBlank @Size(max = 128)
    String embeddingModel
) {
}
