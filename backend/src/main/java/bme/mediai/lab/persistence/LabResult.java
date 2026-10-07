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

    // Cenzorált eredménynél ('<', '<=', '>', '>='): a valueNum a határ, nem pontos mérés
    private String comparator;

    // Kvalitatív eredmény szó szerint ("Negatív"); pontosan az egyik: valueNum VAGY valueText
    private String valueText;

    private String unit;

    private BigDecimal refLow;

    private BigDecimal refHigh;

    // Szöveges referencia ("Negatív", "normális")
    private String refText;

    private OffsetDateTime measuredAt;

    private String code;

    protected LabResult() {
    }

    public LabResult(Long patientId, Long documentId, String groupName, String name, BigDecimal valueNum,
            String comparator, String valueText, String unit, BigDecimal refLow, BigDecimal refHigh, String refText,
            OffsetDateTime measuredAt, String code) {
        boolean hasText = valueText != null && !valueText.isBlank();
        if ((valueNum == null) == !hasText) {
            throw new IllegalArgumentException(
                    "A(z) " + name + " eredményéhez pontosan az egyik kell: számérték (value) vagy szöveg (valueText)");
        }
        if (comparator != null && valueNum == null) {
            throw new IllegalArgumentException("A(z) " + name + ": összehasonlító jel csak számértékhez adható");
        }
        if (refLow != null && refHigh != null && refLow.compareTo(refHigh) > 0) {
            throw new IllegalArgumentException(
                    "A(z) " + name + " referencia-tartománya fordított: " + refLow + " > " + refHigh);
        }
        this.patientId = patientId;
        this.documentId = documentId;
        this.groupName = groupName;
        this.name = name;
        this.valueNum = valueNum;
        this.comparator = comparator;
        this.valueText = hasText ? valueText.strip() : null;
        this.unit = unit;
        this.refLow = refLow;
        this.refHigh = refHigh;
        this.refText = refText == null || refText.isBlank() ? null : refText.strip();
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

    public String getValueText() {
        return valueText;
    }

    public String getRefText() {
        return refText;
    }

    public String getComparator() {
        return comparator;
    }

    public String getCode() {
        return code;
    }
}
