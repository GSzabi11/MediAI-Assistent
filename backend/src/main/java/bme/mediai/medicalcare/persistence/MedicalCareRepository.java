package bme.mediai.medicalcare.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicalCareRepository extends JpaRepository<MedicalCare, Long> {

    List<MedicalCare> findByPatientIdOrderByStartedAtDesc(Long patientId);

    // Atomikus: ha két kérés egyszerre zárná, csak az egyik kap 1-et vissza.
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE medical_care SET ended_at = now() WHERE id = :id AND ended_at IS NULL",
            nativeQuery = true)
    int close(@Param("id") Long id);
}
