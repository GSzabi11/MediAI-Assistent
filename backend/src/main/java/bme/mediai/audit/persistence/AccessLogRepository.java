package bme.mediai.audit.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {

    List<AccessLog> findByPatientIdOrderByAccessedAtDescIdDesc(Long patientId);

    List<AccessLog> findByUserIdOrderByAccessedAtDescIdDesc(Long userId);
}
