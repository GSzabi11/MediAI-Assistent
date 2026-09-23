package bme.mediai.document.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    boolean existsByDocumentId(Long documentId);

    List<DocumentChunk> findByDocumentIdOrderByCharStartAscCharEndAsc(Long documentId);
}
