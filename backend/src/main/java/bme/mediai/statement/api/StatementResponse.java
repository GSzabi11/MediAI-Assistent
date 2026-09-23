package bme.mediai.statement.api;

import java.time.OffsetDateTime;

import bme.mediai.statement.persistence.ApprovalStatus;
import bme.mediai.statement.persistence.Statement;
import bme.mediai.statement.persistence.StatementApproval;
import bme.mediai.statement.persistence.StatementProducer;
import bme.mediai.statement.persistence.StatementSource;
import bme.mediai.statement.persistence.StatementType;
import bme.mediai.statement.persistence.Severity;
import bme.mediai.statement.persistence.Uncertainty;

public record StatementResponse(
        Long id,
        Long patientId,
        StatementType type,
        String text,
        String label,
        Severity severity,
        Uncertainty uncertainty,
        StatementSource source,
        StatementProducer producer,
        OffsetDateTime clinicalTime,
        OffsetDateTime createdAt,
        ReviewStatus reviewStatus,
        // Az orvos által módosított szöveg, ha a legfrissebb döntés MODIFIED; különben az eredeti
        String effectiveText,
        Long reviewedBy,
        OffsetDateTime reviewedAt
) {
    // latestApproval: a legfrissebb jóváhagyás, vagy null, ha még nincs döntés
    public static StatementResponse from(Statement statement, StatementApproval latestApproval) {
        boolean modified = latestApproval != null && latestApproval.getStatus() == ApprovalStatus.MODIFIED;
        return new StatementResponse(
                statement.getId(), statement.getPatientId(), statement.getType(), statement.getText(), statement.getLabel(),
                statement.getSeverity(), statement.getUncertainty(), statement.getSource(), statement.getProducer(),
                statement.getClinicalTime(), statement.getCreatedAt(),
                ReviewStatus.of(latestApproval),
                modified ? latestApproval.getModifiedText() : statement.getText(),
                latestApproval == null ? null : latestApproval.getUserId(),
                latestApproval == null ? null : latestApproval.getDecidedAt());
    }
}
