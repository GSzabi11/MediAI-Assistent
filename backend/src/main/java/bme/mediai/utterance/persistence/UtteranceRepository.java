package bme.mediai.utterance.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UtteranceRepository extends JpaRepository<Utterance, Long> {

    boolean existsByRecordingId(Long recordingId);

    List<Utterance> findByRecordingIdOrderByTurnId(Long recordingId);
}
