package bme.mediai.medicalcare.persistence;

import java.time.OffsetDateTime;

import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "medical_care")
public class MedicalCare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long patientId;

    private Long userId;

    @Enumerated(EnumType.STRING)
    private MedicalCareType type;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime startedAt;

    // A lezárást a MedicalCareRepository.close() írja, DB-idővel (mint a started_at)
    @Column(insertable = false, updatable = false)
    private OffsetDateTime endedAt;

    protected MedicalCare() {
    }

    public MedicalCare(Long patientId, Long userId, MedicalCareType type) {
        this.patientId = patientId;
        this.userId = userId;
        this.type = type;
    }

    public boolean isClosed() {
        return endedAt != null;
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Long getUserId() {
        return userId;
    }

    public MedicalCareType getType() {
        return type;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }
}
