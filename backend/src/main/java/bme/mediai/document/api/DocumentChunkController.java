package bme.mediai.document.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import bme.mediai.document.service.DocumentChunkService;

import java.util.List;

@RestController
@RequestMapping("/api/documents/{documentId}/chunks")
public class DocumentChunkController {

    private final DocumentChunkService service;

    public DocumentChunkController(DocumentChunkService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<ChunkResponse> createAll(
            @PathVariable Long documentId, @Valid @RequestBody ChunkBatchRequest request) {
        return service.createAll(documentId, request.embeddingModel(), request.chunks());
    }

    @GetMapping
    public List<ChunkResponse> findAll(@PathVariable Long documentId) {
        return service.findByDocument(documentId);
    }
}
