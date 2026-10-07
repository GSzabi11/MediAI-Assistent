package bme.mediai.lab.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
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

    // MERRE: a sor helye a lelet PDF-jében (oldal + normált téglalap); NULL, ha nem ismert
    // Explicit oszlopnév: a névképzés a mező végi egybetűs nagybetűnél (sourceX) nem tesz
    // aláhúzást ("sourcex"), és a séma-ellenőrzés elbukna
    @Column(name = "source_page")
    private Integer sourcePage;
    @Column(name = "source_x")
    private Double sourceX;
    @Column(name = "source_y")
    private Double sourceY;
    @Column(name = "source_width")
    private Double sourceWidth;
    @Column(name = "source_height")
    private Double sourceHeight;

    protected LabResult() {
    }

    public LabResult(Long patientId, Long documentId, String groupName, String name, BigDecimal valueNum,
            String comparator, String valueText, String unit, BigDecimal refLow, BigDecimal refHigh, String refText,
            OffsetDateTime measuredAt, String code, Integer sourcePage, Double sourceX, Double sourceY,
            Double sourceWidth, Double sourceHeight) {
        if (sourcePage != null && documentId == null) {
            throw new IllegalArgumentException("A(z) " + name + ": forrás-pozíció csak lelethez (documentId) adható");
        }
        if (sourcePage != null && (sourceX + sourceWidth > 1 || sourceY + sourceHeight > 1)) {
            throw new IllegalArgumentException("A(z) " + name + ": a forrás-téglalap kilóg a lapról");
        }
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
        this.sourcePage = sourcePage;
        this.sourceX = sourceX;
        this.sourceY = sourceY;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
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

    public Integer getSourcePage() {
        return sourcePage;
    }

    public Double getSourceX() {
        return sourceX;
    }

    public Double getSourceY() {
        return sourceY;
    }

    public Double getSourceWidth() {
        return sourceWidth;
    }

    public Double getSourceHeight() {
        return sourceHeight;
    }

    public String getCode() {
        return code;
    }
}
