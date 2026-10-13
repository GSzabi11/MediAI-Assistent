package bme.mediai.keyword.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecordingKeywordRepository extends JpaRepository<RecordingKeyword, Long> {

    List<RecordingKeyword> findByRecordingIdOrderByCreatedAtAscIdAsc(Long recordingId);

    // Ugyanaz a normalizálás, mint az egyedi indexben (lower(btrim(term)))
    @Query("""
            select count(k) > 0 from RecordingKeyword k
             where k.recordingId = :recordingId and lower(trim(k.term)) = lower(trim(:term))
            """)
    boolean existsTerm(@Param("recordingId") Long recordingId, @Param("term") String term);
}
