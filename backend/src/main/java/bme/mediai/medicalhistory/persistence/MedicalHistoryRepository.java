package bme.mediai.medicalhistory.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MedicalHistoryRepository extends JpaRepository<MedicalHistory, Long> {

    List<MedicalHistory> findByPatientIdOrderByRequestedAtDescIdDesc(Long patientId);

    boolean existsByPatientIdAndStatus(Long patientId, MedicalHistoryStatus status);

    List<MedicalHistory> findByPatientIdAndStatusAndRequestedAtBefore(Long patientId, MedicalHistoryStatus status,
            OffsetDateTime before);

    // Beragadt feladat lezárása — atomikus, csak PROCESSING-ből (ha közben megjött az eredmény, 0 sor)
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE medical_history SET status = 'FAILED', completed_at = now(), error = :error
             WHERE id = :id AND status = 'PROCESSING'
            """, nativeQuery = true)
    int markFailedIfProcessing(@Param("id") Long id, @Param("error") String error);

    // "Elavult": a kórtörténet óta új dokumentum vagy állítás került a beteghez
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM document WHERE patient_id = :patientId AND uploaded_at > :since)
                OR EXISTS (SELECT 1 FROM statement WHERE patient_id = :patientId AND created_at > :since)
            """, nativeQuery = true)
    boolean hasNewDataSince(@Param("patientId") Long patientId, @Param("since") OffsetDateTime since);
}
