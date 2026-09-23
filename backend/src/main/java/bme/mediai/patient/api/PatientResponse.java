package bme.mediai.patient.api;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import bme.mediai.patient.persistence.Patient;

public record PatientResponse(
        Long id,
        String name,
        String taj,
        String motherName,
        LocalDate birthDate,
        String address,
        OffsetDateTime letrehozva
) {
    public static PatientResponse from(Patient patient) {
        return new PatientResponse(
                patient.getId(), patient.getName(), patient.getTaj(), patient.getMotherName(), 
                patient.getBirthDate(), patient.getAddress(), patient.getCreatedAt());
    }
}