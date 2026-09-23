package bme.mediai.allergy.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AllergyRepository extends JpaRepository<Allergy, Long> {

    List<Allergy> findByPatientIdOrderBySubstance(Long patientId);

    // Ugyanaz a normalizálás, mint az allergy_patient_substance_idx indexben
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM allergy
                           WHERE patient_id = :patientId AND lower(btrim(substance)) = lower(btrim(:substance)))
            """, nativeQuery = true)
    boolean existsForPatient(@Param("patientId") Long patientId, @Param("substance") String substance);
}
