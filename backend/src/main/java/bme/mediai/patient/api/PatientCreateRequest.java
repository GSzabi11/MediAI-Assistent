package bme.mediai.patient.api;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PatientCreateRequest(

    @NotBlank(message = "The name must not be blank")
    String name,


    @Pattern(regexp = "^[0-9]{9}$", message = "The taj must be exactly 9 digits")
    String taj,

    @NotBlank(message = "The mother's name must not be blank")
    String motherName,

    @NotNull 
    LocalDate birthDate,

    String address
) 
{}
