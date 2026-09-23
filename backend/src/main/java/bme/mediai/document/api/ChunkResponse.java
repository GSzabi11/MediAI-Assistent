package bme.mediai.document.api;

import bme.mediai.document.persistence.DocumentChunk;

// Az embedding nincs benne (1024 float chunkonként): a válasz a szövegre és a pozícióra szól.
public record ChunkResponse(
        Long id,
        Long documentId,
        String text,
        Integer charStart,
        Integer charEnd,
        String embeddingModel
) {
    public static ChunkResponse from(DocumentChunk chunk) {
        return new ChunkResponse(
                chunk.getId(), chunk.getDocumentId(), chunk.getText(),
                chunk.getCharStart(), chunk.getCharEnd(), chunk.getEmbeddingModel());
    }
}
