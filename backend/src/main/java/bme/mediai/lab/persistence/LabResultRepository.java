package bme.mediai.lab.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LabResultRepository extends JpaRepository<LabResult, Long> {

    List<LabResult> findByPatientIdOrderByMeasuredAtDescIdDesc(Long patientId);

    // Trend: egy vizsgálat időrendben (lab_result_patient_name_time_idx)
    List<LabResult> findByPatientIdAndNameOrderByMeasuredAtAscIdAsc(Long patientId, String name);
}
