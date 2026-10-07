package bme.mediai.lab.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import bme.mediai.lab.persistence.LabResult;
import bme.mediai.lab.service.LabTestNames;

public record LabResultResponse(
        Long id,
        Long patientId,
        Long documentId,
        String groupName,
        // a leleten álló név, változatlanul
        String name,
        // egységes név a trendhez ("Kálium (K) (A)" → "Kálium"; LabTestNames) — ez szerint csoportosíts
        String canonicalName,
        BigDecimal value,
        // null: pontos érték; különben a value a határ (pl. ">" 90)
        String comparator,
        // kvalitatív eredmény (ilyenkor value null)
        String valueText,
        String unit,
        // egységes írásmód a trendhez ("Giga/L" → "G/L"); csak 1:1 megfelelés, átváltás nincs
        String canonicalUnit,
        BigDecimal refLow,
        BigDecimal refHigh,
        String refText,
        OffsetDateTime measuredAt,
        String code
) {
    public static LabResultResponse from(LabResult result) {
        return new LabResultResponse(
                result.getId(), result.getPatientId(), result.getDocumentId(), result.getGroupName(),
                result.getName(), LabTestNames.canonical(result.getName()), result.getValueNum(), result.getComparator(), result.getValueText(), result.getUnit(),
                LabTestNames.canonicalUnit(result.getUnit()),
                result.getRefLow(), result.getRefHigh(), result.getRefText(), result.getMeasuredAt(), result.getCode());
    }
}
