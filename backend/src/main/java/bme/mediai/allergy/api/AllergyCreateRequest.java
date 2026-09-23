package bme.mediai.allergy.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AllergyCreateRequest(

    @NotNull(message = "The patientId must not be null")
    Long patientId,

    @NotBlank(message = "The substance must not be blank")
    @Size(max = 255)
    String substance,

    // Opcionális: az a (jóváhagyott) állítás, amiből az allergia származik
    Long sourceStatementId
)
{}
