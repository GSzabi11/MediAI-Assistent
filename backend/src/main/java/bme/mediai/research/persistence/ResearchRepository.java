package bme.mediai.research.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResearchRepository extends JpaRepository<Research, Long> {

    List<Research> findByPatientIdOrderByCreatedAtDescIdDesc(Long patientId);
}
