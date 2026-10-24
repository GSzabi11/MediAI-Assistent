package bme.mediai.recording.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordingRepository extends JpaRepository<Recording, Long> {

    List<Recording> findByPatientIdOrderByRecordedAtDesc(Long patientId);

    // félbemaradt hangfeldolgozás keresése (VoiceLostJobs)
    List<Recording> findByStatus(RecordingStatus status);
}
