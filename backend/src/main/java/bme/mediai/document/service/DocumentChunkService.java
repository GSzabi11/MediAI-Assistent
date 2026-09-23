package bme.mediai.document.service;

import bme.mediai.document.api.ChunkRequest;
import bme.mediai.document.api.ChunkResponse;
import bme.mediai.document.exception.ChunksAlreadyExistException;
import bme.mediai.document.exception.DocumentNotFoundException;
import bme.mediai.document.exception.ExtractedTextMissingException;
import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentChunk;
import bme.mediai.document.persistence.DocumentChunkRepository;
import bme.mediai.document.persistence.DocumentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class DocumentChunkService {

    private final DocumentChunkRepository repository;
    private final DocumentRepository documentRepository;

    public DocumentChunkService(DocumentChunkRepository repository, DocumentRepository documentRepository) {
        this.repository = repository;
        this.documentRepository = documentRepository;
    }

    @Transactional
    public List<ChunkResponse> createAll(Long documentId, String embeddingModel, List<ChunkRequest> requests) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.getExtractedText() == null) {
            throw new ExtractedTextMissingException(documentId);
        }
        if (repository.existsByDocumentId(documentId)) {
            throw new ChunksAlreadyExistException(documentId);
        }

        Set<String> ranges = new HashSet<>();
        List<DocumentChunk> chunks = new ArrayList<>();
        for (ChunkRequest r : requests) {
            if (!ranges.add(r.charStart() + ":" + r.charEnd())) {
                throw new IllegalArgumentException(
                        "Ismétlődő tartomány a kérésben: [" + r.charStart() + ", " + r.charEnd() + ")");
            }
            validateEmbedding(r);
            // A szöveg a pozícióból származik, így a kettő sosem mondhat ellent egymásnak
            String text = document.textSpan(r.charStart(), r.charEnd());
            if (text.isBlank()) {
                throw new IllegalArgumentException(
                        "A(z) [" + r.charStart() + ", " + r.charEnd() + ") chunk csak whitespace-t tartalmaz");
            }
            chunks.add(new DocumentChunk(documentId, text, r.charStart(), r.charEnd(), r.embedding(), embeddingModel));
        }

        return repository.saveAll(chunks)
                .stream()
                .map(ChunkResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChunkResponse> findByDocument(Long documentId) {
        if (!documentRepository.existsById(documentId)) {
            throw new DocumentNotFoundException(documentId);
        }
        return repository.findByDocumentIdOrderByCharStartAscCharEndAsc(documentId)
                .stream()
                .map(ChunkResponse::from)
                .toList();
    }

    private static void validateEmbedding(ChunkRequest r) {
        float[] embedding = r.embedding();
        if (embedding.length != DocumentChunk.EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException("Az embedding " + DocumentChunk.EMBEDDING_DIMENSION
                    + " dimenziós kell legyen, ez " + embedding.length + " ([" + r.charStart() + ", "
                    + r.charEnd() + ") chunk)");
        }
        for (float v : embedding) {
            if (!Float.isFinite(v)) {
                throw new IllegalArgumentException("Az embedding nem tartalmazhat NaN/Infinity értéket");
            }
        }
    }
}
