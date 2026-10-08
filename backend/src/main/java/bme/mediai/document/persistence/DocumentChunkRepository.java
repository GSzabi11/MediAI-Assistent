package bme.mediai.document.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    boolean existsByDocumentId(Long documentId);

    List<DocumentChunk> findByDocumentIdOrderByCharStartAscCharEndAsc(Long documentId);

    interface NearestChunk {
        Long getId();

        Long getDocumentId();

        Integer getCharStart();

        Integer getCharEnd();

        String getText();

        double getDistance();
    }

    // Egy beteg darabjai a kérdés-vektorhoz legközelebb (koszinusz-távolság), csak azonos
    // embedding-modell vektoraival. A MATERIALIZED CTE szándékos: a beteg néhány tucat darabján
    // pontos keresés fut. A HNSW-index a szűrést a keresés UTÁN végezné (ef_search jelöltből),
    // így sok beteg mellett egy beteg darabjai csendben kimaradhatnának.
    //
    // Sablonszöveg-szűrés: ha egy darab egy MÁSIK, újabb dokumentumban is szinte szó szerint megvan
    // (koszinusz-távolság < :duplicateDistance — aláírás-blokk, EESZT-lábléc, tájékoztató), csak a
    // legújabb példány marad. Az első próbán a 8 találatból 6 ilyen sablon volt, a tartalom kiszorult.
    // Valódi, több leleten ismétlődő tartalom (pl. ugyanaz a diagnózis) így sem vész el: a legfrissebb marad.
    @Query(value = """
            WITH patient_chunks AS MATERIALIZED (
                SELECT c.id, c.document_id, c.char_start, c.char_end, c.text, c.embedding,
                       COALESCE(d.clinical_date, CAST(d.uploaded_at AS date)) AS doc_date
                  FROM document_chunk c JOIN document d ON d.id = c.document_id
                 WHERE d.patient_id = :patientId AND c.embedding_model = :embeddingModel
            )
            SELECT p.id, p.document_id AS documentId, p.char_start AS charStart, p.char_end AS charEnd, p.text,
                   p.embedding <=> CAST(:embedding AS vector) AS distance
              FROM patient_chunks p
             WHERE NOT EXISTS (
                   SELECT 1 FROM patient_chunks o
                    WHERE o.document_id <> p.document_id
                      AND (o.doc_date, o.document_id) > (p.doc_date, p.document_id)
                      AND (o.embedding <=> p.embedding) < :duplicateDistance)
             ORDER BY distance, p.id
             LIMIT :limit
            """, nativeQuery = true)
    List<NearestChunk> findNearestForPatient(@Param("patientId") Long patientId,
            @Param("embeddingModel") String embeddingModel, @Param("embedding") String embedding,
            @Param("duplicateDistance") double duplicateDistance, @Param("limit") int limit);
}
