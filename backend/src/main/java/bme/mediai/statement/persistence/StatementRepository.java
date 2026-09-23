package bme.mediai.statement.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    List<Statement> findByPatientIdOrderByClinicalTimeDesc(Long patientId);
}
