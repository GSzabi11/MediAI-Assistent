package bme.mediai.lab.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import bme.mediai.lab.persistence.LabResult;

public record LabResultResponse(
        Long id,
        Long patientId,
        Long documentId,
        String groupName,
        String name,
        BigDecimal value,
        String unit,
        BigDecimal refLow,
        BigDecimal refHigh,
        OffsetDateTime measuredAt,
        String code
) {
    public static LabResultResponse from(LabResult result) {
        return new LabResultResponse(
                result.getId(), result.getPatientId(), result.getDocumentId(), result.getGroupName(),
                result.getName(), result.getValueNum(), result.getUnit(), result.getRefLow(),
                result.getRefHigh(), result.getMeasuredAt(), result.getCode());
    }
}
