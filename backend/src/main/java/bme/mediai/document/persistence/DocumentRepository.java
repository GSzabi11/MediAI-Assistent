package bme.mediai.document.persistence;

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
}
