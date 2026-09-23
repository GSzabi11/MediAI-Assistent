package bme.mediai.patient.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pacient")
public class Patient {
    
    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String motherName;

    private LocalDate birthDate;

    private String taj;
    
    private String address;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Patient() {
    }

    public Patient(String name, String motherName, LocalDate birthDate, String taj, String address) {
        this.name = name;
        this.motherName = motherName;
        this.birthDate = birthDate;
        this.taj = taj;
        this.address = address;
    }

    public Long getId() {
        return id;
    }
    public String getAddress() {
        return address;
    }

    public String getName() {
        return name;
    }

    public String getMotherName() {
        return motherName;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getTaj() {
        return taj;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }


}
