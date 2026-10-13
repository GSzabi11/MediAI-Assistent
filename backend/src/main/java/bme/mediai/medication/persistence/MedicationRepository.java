package bme.mediai.medication.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicationRepository extends JpaRepository<Medication, Long> {

    List<Medication> findByPatientIdOrderByIdDesc(Long patientId);

    List<Medication> findByPatientIdAndActiveTrueOrderByIdDesc(Long patientId);

    // Azonos nevű aktív tételek — kis-/nagybetű és szélső szóközök nélkül, mint az allergia egyedi indexe
    @Query(value = """
            SELECT * FROM medication
            WHERE patient_id = :patientId AND active
              AND lower(btrim(name)) = lower(btrim(:name))
            ORDER BY id
            """, nativeQuery = true)
    List<Medication> findActiveByPatientAndName(@Param("patientId") Long patientId, @Param("name") String name);
}
