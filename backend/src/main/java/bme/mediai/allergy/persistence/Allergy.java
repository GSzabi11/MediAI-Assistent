package bme.mediai.allergy.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Megváltoztathatatlan: nincs módosító metódus.
@Entity
@Table(name = "allergy")
public class Allergy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long patientId;

    private String substance;

    private Long sourceStatementId;

    protected Allergy() {
    }

    public Allergy(Long patientId, String substance, Long sourceStatementId) {
        this.patientId = patientId;
        this.substance = substance.strip();
        this.sourceStatementId = sourceStatementId;
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public String getSubstance() {
        return substance;
    }

    public Long getSourceStatementId() {
        return sourceStatementId;
    }
}
