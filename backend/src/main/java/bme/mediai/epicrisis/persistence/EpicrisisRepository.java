package bme.mediai.epicrisis.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EpicrisisRepository extends JpaRepository<Epicrisis, Long> {

    List<Epicrisis> findByPatientIdOrderByRequestedAtDescIdDesc(Long patientId);

    boolean existsByMedicalCareIdAndStatus(Long medicalCareId, EpicrisisStatus status);

    List<Epicrisis> findByPatientIdAndStatusAndRequestedAtBefore(Long patientId, EpicrisisStatus status,
            OffsetDateTime before);

    // Beragadt feladat lezárása — atomikus, csak PROCESSING-ből (ha közben megjött az eredmény, 0 sor)
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE epicrisis SET status = 'FAILED', completed_at = now(), error = :error
             WHERE id = :id AND status = 'PROCESSING'
            """, nativeQuery = true)
    int markFailedIfProcessing(@Param("id") Long id, @Param("error") String error);
}
