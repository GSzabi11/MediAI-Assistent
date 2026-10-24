package bme.mediai.recordingfinding.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordingExtractionRepository extends JpaRepository<RecordingExtraction, Long> {

    Optional<RecordingExtraction> findFirstByRecordingIdOrderByRequestedAtDescIdDesc(Long recordingId);

    Optional<RecordingExtraction> findFirstByRecordingIdAndStatusOrderByRequestedAtDescIdDesc(Long recordingId,
            RecordingExtractionStatus status);

    boolean existsByRecordingIdAndStatus(Long recordingId, RecordingExtractionStatus status);

    // beragadt csoport-kinyerés keresése (VoiceLostJobs)
    List<RecordingExtraction> findByStatusAndRequestedAtBefore(RecordingExtractionStatus status,
            OffsetDateTime before);
}
