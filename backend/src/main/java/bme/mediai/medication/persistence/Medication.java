package bme.mediai.medication.persistence;

import java.math.BigDecimal;

import bme.mediai.common.persistence.EntryOrigin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "medication")
public class Medication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long patientId;

    private String name;

    private String activeIngredient;

    private BigDecimal dose;

    private String doseUnit;

    private String route;

    private Long sourceStatementId;

    private boolean active;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private EntryOrigin origin;

    protected Medication() {
    }

    public Medication(Long patientId, String name, String activeIngredient, BigDecimal dose,
            String doseUnit, String route, Long sourceStatementId, EntryOrigin origin) {
        if (origin == EntryOrigin.DOCUMENT_PIPELINE && sourceStatementId == null) {
            throw new IllegalArgumentException("A pipeline-ból jött gyógyszerhez kötelező a forrás-állítás");
        }
        if (dose != null && (doseUnit == null || doseUnit.isBlank())) {
            throw new IllegalArgumentException("Dózishoz kötelező a mértékegység (doseUnit)");
        }
        this.patientId = patientId;
        this.name = name;
        this.activeIngredient = activeIngredient;
        this.dose = dose;
        this.doseUnit = doseUnit;
        this.route = route;
        this.sourceStatementId = sourceStatementId;
        this.active = true;
        this.origin = origin;
    }

    // Egyirányú: leállított gyógyszer nem kapcsolható vissza, újrakezdés = új sor.
    public void discontinue() {
        if (!active) {
            throw new IllegalStateException("already discontinued");
        }
        this.active = false;
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public String getName() {
        return name;
    }

    public String getActiveIngredient() {
        return activeIngredient;
    }

    public BigDecimal getDose() {
        return dose;
    }

    public String getDoseUnit() {
        return doseUnit;
    }

    public String getRoute() {
        return route;
    }

    public Long getSourceStatementId() {
        return sourceStatementId;
    }

    public EntryOrigin getOrigin() {
        return origin;
    }

    public boolean isActive() {
        return active;
    }
}
