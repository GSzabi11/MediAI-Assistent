package bme.mediai.statement.api;

import bme.mediai.statement.persistence.StatementApproval;

// Az állítás aktuális állapota a legfrissebb jóváhagyás alapján; PENDING, ha még nincs döntés.
public enum ReviewStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    MODIFIED;

    public static ReviewStatus of(StatementApproval latest) {
        return latest == null ? PENDING : ReviewStatus.valueOf(latest.getStatus().name());
    }
}
