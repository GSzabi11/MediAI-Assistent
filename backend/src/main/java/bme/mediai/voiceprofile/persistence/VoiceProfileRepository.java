package bme.mediai.voiceprofile.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceProfileRepository extends JpaRepository<VoiceProfile, Long> {

    Optional<VoiceProfile> findByUserIdAndActiveTrue(Long userId);

    List<VoiceProfile> findByUserIdOrderByRecordedAtDesc(Long userId);

    boolean existsByUserIdAndStatus(Long userId, VoiceProfileStatus status);

    // beragadt hangprofil keresése (VoiceLostJobs)
    List<VoiceProfile> findByStatusAndCreatedAtBefore(VoiceProfileStatus status, OffsetDateTime before);
}
