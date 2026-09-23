package bme.mediai.patient.api;

import java.time.LocalDate;

public record PatientSearchRequest(
        String taj,
        String name,
        String motherName,
        LocalDate birthDate
) {}