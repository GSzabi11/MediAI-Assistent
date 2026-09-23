package bme.mediai.statement.persistence;

import java.time.OffsetDateTime;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Megváltoztathatatlan: nincs módosító metódus. A jóváhagyás külön tábla lesz
// (allitas_jovahagyas). Az invariánsokat a DB CHECK-ek is kikényszerítik.
@Entity
@Table(name = "statement")
public class Statement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long patientId;

    @Enumerated(EnumType.STRING)
    private StatementType type;

    private String text;

    private String label;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    private Uncertainty uncertainty;

    @JdbcTypeCode(SqlTypes.JSON)
    private StatementSource source;

    @JdbcTypeCode(SqlTypes.JSON)
    private StatementProducer producer;

    private OffsetDateTime clinicalTime;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Statement() {
    }

    public Statement(Long patientId, StatementType type, String text, String label, Severity severity,
            Uncertainty uncertainty, StatementSource source, StatementProducer producer, OffsetDateTime clinicalTime) {
        require(patientId != null, "A patientId kötelező");
        require(type != null && severity != null, "A type és a severity kötelező");
        require(text != null && !text.isBlank(), "A szöveg (MI) kötelező");
        require(source != null, "A forrás (MERRE) kötelező");
        require(producer != null, "Az előállító (HOGY) kötelező");
        require(clinicalTime != null, "A klinikai idő (MIKOR) kötelező");

        require(type != StatementType.DETERMINISTIC_RULE || uncertainty == null,
                "Szabály-alapú állításnak nem lehet bizonytalansága");
        require(type != StatementType.MODEL_INFERENCE || uncertainty != null,
                "Modell-következtetéshez kötelező a bizonytalanság");
        require(producerMatchesType(type, producer),
                "Az előállító fajtája nem illik a típushoz: " + type);

        source.validate();
        producer.validate();

        this.patientId = patientId;
        this.type = type;
        this.text = text;
        this.label = label;
        this.severity = severity;
        this.uncertainty = uncertainty;
        this.source = source;
        this.producer = producer;
        this.clinicalTime = clinicalTime;
    }

    private static boolean producerMatchesType(StatementType type, StatementProducer producer) {
        return switch (type) {
            case DETERMINISTIC_RULE -> producer instanceof StatementProducer.Rule;
            case MODEL_INFERENCE -> producer instanceof StatementProducer.Model;
            case RETRIEVED_FACT -> producer instanceof StatementProducer.Extractor;
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public StatementType getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public String getLabel() {
        return label;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Uncertainty getUncertainty() {
        return uncertainty;
    }

    public StatementSource getSource() {
        return source;
    }

    public StatementProducer getProducer() {
        return producer;
    }

    public OffsetDateTime getClinicalTime() {
        return clinicalTime;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
