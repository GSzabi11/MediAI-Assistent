package bme.mediai.audit.persistence;

import java.net.InetAddress;
import java.time.OffsetDateTime;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;
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

// Csak hozzáfűzés: @Immutable a Hibernate-ben, trigger a DB-ben.
@Entity
@Immutable
@Table(name = "access_log")
public class AccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private Long patientId;

    @Enumerated(EnumType.STRING)
    private AccessAction action;

    private String resource;

    private Integer httpStatus;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime accessedAt;

    @JdbcTypeCode(SqlTypes.INET)
    private InetAddress ip;

    protected AccessLog() {
    }

    public AccessLog(Long userId, Long patientId, AccessAction action, String resource,
            Integer httpStatus, InetAddress ip) {
        this.userId = userId;
        this.patientId = patientId;
        this.action = action;
        this.resource = resource;
        this.httpStatus = httpStatus;
        this.ip = ip;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPatientId() {
        return patientId;
    }

    public AccessAction getAction() {
        return action;
    }

    public String getResource() {
        return resource;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public OffsetDateTime getAccessedAt() {
        return accessedAt;
    }

    public InetAddress getIp() {
        return ip;
    }
}
