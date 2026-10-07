package bme.mediai.lab.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LabResultRepository extends JpaRepository<LabResult, Long> {

    List<LabResult> findByPatientIdOrderByMeasuredAtDescIdDesc(Long patientId);

    // a szabálymotor indulási pótlásához
    @Query("select distinct r.patientId from LabResult r")
    List<Long> findPatientIdsWithResults();
}
