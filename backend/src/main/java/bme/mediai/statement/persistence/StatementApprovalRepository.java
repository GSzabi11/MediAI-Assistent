package bme.mediai.statement.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// A "legfrissebb sor az érvényes" szabály egyetlen helyen: itt, a rendezésben
// (decided_at DESC, id DESC — az id dönt, ha két döntés ugyanabban a pillanatban született).
public interface StatementApprovalRepository extends JpaRepository<StatementApproval, Long> {

    Optional<StatementApproval> findFirstByStatementIdOrderByDecidedAtDescIdDesc(Long statementId);

    List<StatementApproval> findByStatementIdOrderByDecidedAtDescIdDesc(Long statementId);

    // Listázáshoz, N+1 lekérdezés helyett: állításonként a legfrissebb döntés
    @Query(value = """
            SELECT DISTINCT ON (statement_id) *
            FROM statement_approval
            WHERE statement_id IN (:statementIds)
            ORDER BY statement_id, decided_at DESC, id DESC
            """, nativeQuery = true)
    List<StatementApproval> findLatestForStatements(@Param("statementIds") Collection<Long> statementIds);
}
