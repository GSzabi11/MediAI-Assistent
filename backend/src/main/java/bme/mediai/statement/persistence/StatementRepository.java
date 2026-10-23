package bme.mediai.statement.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    List<Statement> findByPatientIdOrderByClinicalTimeDesc(Long patientId);

    // Egy szabály LAB forrású állításai egy betegnél (a szabálymotor egyeztetéséhez)
    @Query(value = """
            SELECT * FROM statement
             WHERE patient_id = :patientId AND source ->> 'type' = 'LAB' AND producer ->> 'ruleId' = :ruleId
            """, nativeQuery = true)
    List<Statement> findLabRuleStatements(@Param("patientId") Long patientId, @Param("ruleId") String ruleId);

    // Egy szabály MEDICATION forrású állításai egy betegnél (az interakció-szabály egyeztetéséhez)
    @Query(value = """
            SELECT * FROM statement
             WHERE patient_id = :patientId AND source ->> 'type' = 'MEDICATION' AND producer ->> 'ruleId' = :ruleId
            """, nativeQuery = true)
    List<Statement> findMedicationRuleStatements(@Param("patientId") Long patientId, @Param("ruleId") String ruleId);
}
