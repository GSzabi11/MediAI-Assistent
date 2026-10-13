package bme.mediai.recordingsummary.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordingSummaryRepository extends JpaRepository<RecordingSummary, Long> {

    List<RecordingSummary> findByRecordingIdOrderByRequestedAtDescIdDesc(Long recordingId);

    boolean existsByRecordingIdAndStatus(Long recordingId, RecordingSummaryStatus status);
}
