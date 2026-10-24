package bme.mediai.research.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchContextRepository extends JpaRepository<ResearchContext, Long> {

    List<ResearchContext> findByPatientIdOrderByRequestedAtDescIdDesc(Long patientId);

    boolean existsByPatientIdAndStatus(Long patientId, ResearchContextStatus status);

    // beragadt feladat keresése (ResearchLostJobs)
    List<ResearchContext> findByStatusAndRequestedAtBefore(ResearchContextStatus status, OffsetDateTime before);
}
