package bme.mediai.recordingfinding.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecordingFindingRepository extends JpaRepository<RecordingFinding, Long> {

    // Az érvényes lista: a legfrissebb kész futás AI-elemei (extractionId; null, ha még nincs) + az orvos
    // elemei, a törölt nélkül
    @Query("""
            select f from RecordingFinding f
             where f.recordingId = :recordingId and f.deletedAt is null
               and (f.origin = bme.mediai.recordingfinding.persistence.FindingOrigin.DOCTOR
                    or f.extractionId = :extractionId)
             order by f.id
            """)
    List<RecordingFinding> findCurrent(@Param("recordingId") Long recordingId, @Param("extractionId") Long extractionId);
}
