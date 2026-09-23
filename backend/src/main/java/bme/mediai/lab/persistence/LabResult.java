package bme.mediai.lab.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Megváltoztathatatlan: nincs módosító metódus.
@Entity
@Table(name = "lab_result")
public class LabResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long patientId;

    private Long documentId;

    private String groupName;

    private String name;

    private BigDecimal valueNum;

    private String unit;

    private BigDecimal refLow;

    private BigDecimal refHigh;

    private OffsetDateTime measuredAt;

    private String code;

    protected LabResult() {
    }

    public LabResult(Long patientId, Long documentId, String groupName, String name, BigDecimal valueNum,
            String unit, BigDecimal refLow, BigDecimal refHigh, OffsetDateTime measuredAt, String code) {
        if (refLow != null && refHigh != null && refLow.compareTo(refHigh) > 0) {
            throw new IllegalArgumentException(
                    "A(z) " + name + " referencia-tartománya fordított: " + refLow + " > " + refHigh);
        }
        this.patientId = patientId;
        this.documentId = documentId;
        this.groupName = groupName;
        this.name = name;
        this.valueNum = valueNum;
        this.unit = unit;
        this.refLow = refLow;
        this.refHigh = refHigh;
        this.measuredAt = measuredAt;
        this.code = code;
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getGroupName() {
        return groupName;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getValueNum() {
        return valueNum;
    }

    public String getUnit() {
        return unit;
    }

    public BigDecimal getRefLow() {
        return refLow;
    }

    public BigDecimal getRefHigh() {
        return refHigh;
    }

    public OffsetDateTime getMeasuredAt() {
        return measuredAt;
    }

    public String getCode() {
        return code;
    }
}
