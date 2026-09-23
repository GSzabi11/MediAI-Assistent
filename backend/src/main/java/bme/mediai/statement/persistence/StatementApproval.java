package bme.mediai.statement.persistence;

import java.time.OffsetDateTime;

import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Megváltoztathatatlan: a véleményváltozás új sor, nem módosítás.
@Entity
@Table(name = "statement_approval")
public class StatementApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long statementId;

    private Long userId;

    @Enumerated(EnumType.STRING)
    private ApprovalStatus status;

    private String modifiedText;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime decidedAt;

    protected StatementApproval() {
    }

    public StatementApproval(Long statementId, Long userId, ApprovalStatus status, String modifiedText) {
        boolean isModified = status == ApprovalStatus.MODIFIED;
        boolean hasText = modifiedText != null && !modifiedText.isBlank();
        if (isModified != hasText) {
            throw new IllegalArgumentException(isModified
                    ? "MODIFIED státuszhoz kötelező a modifiedText"
                    : "modifiedText csak MODIFIED státusznál adható meg");
        }
        this.statementId = statementId;
        this.userId = userId;
        this.status = status;
        this.modifiedText = modifiedText;
    }

    public Long getId() {
        return id;
    }

    public Long getStatementId() {
        return statementId;
    }

    public Long getUserId() {
        return userId;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public String getModifiedText() {
        return modifiedText;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }
}
