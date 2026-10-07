package bme.mediai.allergy.persistence;

import bme.mediai.common.persistence.EntryOrigin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private EntryOrigin origin;

    protected Allergy() {
    }

    public Allergy(Long patientId, String substance, Long sourceStatementId, EntryOrigin origin) {
        if (origin == EntryOrigin.DOCUMENT_PIPELINE && sourceStatementId == null) {
            throw new IllegalArgumentException("A pipeline-ból jött allergiához kötelező a forrás-állítás");
        }
        this.patientId = patientId;
        this.substance = substance.strip();
        this.sourceStatementId = sourceStatementId;
        this.origin = origin;
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

    public EntryOrigin getOrigin() {
        return origin;
    }

    public Long getSourceStatementId() {
        return sourceStatementId;
    }
}
