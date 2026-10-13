package bme.mediai.document.persistence;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    boolean existsByPatientIdAndSha256(Long patientId, String sha256);

    List<Document> findByPatientIdOrderByUploadedAtDesc(Long patientId);

    // Atomikus "egyszer beállítható": ha már van szöveg, 0 sort módosít.
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE document SET extracted_text = :text WHERE id = :id AND extracted_text IS NULL",
            nativeQuery = true)
    int setExtractedText(@Param("id") Long id, @Param("text") String text);

    // --- Automatikus feldolgozás: minden állapotváltás atomikus, és csak PROCESSING-ből indulhat,
    // --- így egy ismételt (duplikált) callback 0 sort módosít → 409, nem ír kétszer.

    // A callback első lépése: a kinyert szöveg és a besorolás. A chunkok, állítások ezután
    // hivatkozhatnak a szöveg pozícióira (ugyanabban a tranzakcióban).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document
               SET extracted_text = :text, type = :type, clinical_date = :clinicalDate,
                   extraction_model = :model, extraction_seed = :seed,
                   extraction_prompt_hash = :promptHash, extractor_version = :extractorVersion
             WHERE id = :id AND status = 'PROCESSING' AND extracted_text IS NULL
            """, nativeQuery = true)
    int beginCompletion(@Param("id") Long id, @Param("text") String text, @Param("type") String type,
            @Param("clinicalDate") LocalDate clinicalDate, @Param("model") String model, @Param("seed") Long seed,
            @Param("promptHash") String promptHash, @Param("extractorVersion") String extractorVersion);

    // A callback utolsó lépése: végleges hely (típus szerinti mappa), figyelmeztetések, DB-idő
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document
               SET status = 'PROCESSED', processed_at = now(), storage_path = :storagePath,
                   processing_warnings = to_jsonb(CAST(:warnings AS text[]))
             WHERE id = :id AND status = 'PROCESSING'
            """, nativeQuery = true)
    int finishProcessing(@Param("id") Long id, @Param("storagePath") String storagePath,
            @Param("warnings") String[] warnings);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document
               SET status = 'FAILED', processed_at = now(), processing_error = :error
             WHERE id = :id AND status = 'PROCESSING'
            """, nativeQuery = true)
    int markFailed(@Param("id") Long id, @Param("error") String error);

    // Újrafeldolgozás: FAILED → PROCESSING, a hiba törlődik. Csak PDF-re, és csak ha nincs kinyert
    // szöveg (a kézzel rögzített szövegre már állítások hivatkozhatnak; a sikertelen callback
    // visszagörgetett, így részleges adat nincs). Atomikus: kétszeri kattintás → második 0 sor.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE document
               SET status = 'PROCESSING', processed_at = NULL, processing_error = NULL,
                   processing_warnings = NULL
             WHERE id = :id AND status = 'FAILED' AND extracted_text IS NULL
               AND mime_type = 'application/pdf'
            """, nativeQuery = true)
    int restartProcessing(@Param("id") Long id);
}
