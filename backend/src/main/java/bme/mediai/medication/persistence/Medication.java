package bme.mediai.medication.persistence;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
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

    protected Medication() {
    }

    public Medication(Long patientId, String name, String activeIngredient, BigDecimal dose,
            String doseUnit, String route, Long sourceStatementId) {
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

    public boolean isActive() {
        return active;
    }
}
