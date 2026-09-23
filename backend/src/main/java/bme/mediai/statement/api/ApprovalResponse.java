package bme.mediai.statement.api;

import java.time.OffsetDateTime;

import bme.mediai.statement.persistence.ApprovalStatus;
import bme.mediai.statement.persistence.StatementApproval;

public record ApprovalResponse(
        Long id,
        Long statementId,
        Long userId,
        ApprovalStatus status,
        String modifiedText,
        OffsetDateTime decidedAt
) {
    public static ApprovalResponse from(StatementApproval approval) {
        return new ApprovalResponse(
                approval.getId(), approval.getStatementId(), approval.getUserId(),
                approval.getStatus(), approval.getModifiedText(), approval.getDecidedAt());
    }
}
