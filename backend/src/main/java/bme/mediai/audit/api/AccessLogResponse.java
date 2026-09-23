package bme.mediai.audit.api;

import java.time.OffsetDateTime;

import bme.mediai.audit.persistence.AccessAction;
import bme.mediai.audit.persistence.AccessLog;

public record AccessLogResponse(
        Long id,
        Long userId,
        Long patientId,
        AccessAction action,
        String resource,
        Integer httpStatus,
        OffsetDateTime accessedAt,
        String ip
) {
    public static AccessLogResponse from(AccessLog log) {
        return new AccessLogResponse(
                log.getId(), log.getUserId(), log.getPatientId(), log.getAction(), log.getResource(),
                log.getHttpStatus(), log.getAccessedAt(),
                log.getIp() == null ? null : log.getIp().getHostAddress());
    }
}
