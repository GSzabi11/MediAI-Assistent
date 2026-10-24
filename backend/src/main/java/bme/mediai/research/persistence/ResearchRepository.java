package bme.mediai.research.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchRepository extends JpaRepository<Research, Long> {

    List<Research> findByPatientIdOrderByCreatedAtDescIdDesc(Long patientId);

    // beragadt feladatok keresése (ResearchLostJobs)
    List<Research> findByStatusAndCreatedAtBefore(ResearchStatus status, OffsetDateTime before);

    List<Research> findByRankingStatusAndRankedAtBefore(RankingStatus status, OffsetDateTime before);
}
