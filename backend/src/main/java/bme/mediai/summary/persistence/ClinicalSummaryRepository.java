package bme.mediai.summary.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClinicalSummaryRepository extends JpaRepository<ClinicalSummary, Long> {

    List<ClinicalSummary> findByPatientIdOrderByRequestedAtDescIdDesc(Long patientId);

    boolean existsByPatientIdAndStatus(Long patientId, SummaryStatus status);

    // Atomikus, csak PROCESSING-ből (duplikált callback 0 sort módosít → 409), DB-idővel
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE clinical_summary
               SET status = 'DONE', completed_at = now(), summary = :summary,
                   recommendations = to_jsonb(CAST(:recommendations AS text[])),
                   warnings = to_jsonb(CAST(:warnings AS text[])),
                   model_name = :modelName, seed = :seed, prompt_hash = :promptHash,
                   extractor_version = :extractorVersion
             WHERE id = :id AND status = 'PROCESSING'
            """, nativeQuery = true)
    int complete(@Param("id") Long id, @Param("summary") String summary,
            @Param("recommendations") String[] recommendations, @Param("warnings") String[] warnings,
            @Param("modelName") String modelName, @Param("seed") Long seed,
            @Param("promptHash") String promptHash, @Param("extractorVersion") String extractorVersion);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE clinical_summary SET status = 'FAILED', completed_at = now(), error = :error
             WHERE id = :id AND status = 'PROCESSING'
            """, nativeQuery = true)
    int markFailed(@Param("id") Long id, @Param("error") String error);

    // "Elavult": az összefoglaló óta került új dokumentum vagy állítás a beteghez
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM document WHERE patient_id = :patientId AND uploaded_at > :since)
                OR EXISTS (SELECT 1 FROM statement WHERE patient_id = :patientId AND created_at > :since)
            """, nativeQuery = true)
    boolean hasNewDataSince(@Param("patientId") Long patientId, @Param("since") OffsetDateTime since);
}
