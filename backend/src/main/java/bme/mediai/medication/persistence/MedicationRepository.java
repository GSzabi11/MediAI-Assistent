package bme.mediai.medication.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MedicationRepository extends JpaRepository<Medication, Long> {

    List<Medication> findByPatientIdOrderByIdDesc(Long patientId);

    List<Medication> findByPatientIdAndActiveTrueOrderByIdDesc(Long patientId);
}
