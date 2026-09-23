package bme.mediai.medication.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record MedicationCreateRequest(

    @NotNull(message = "The patientId must not be null")
    Long patientId,

    @NotBlank(message = "The name must not be blank")
    @Size(max = 255)
    String name,

    @Size(max = 255)
    String activeIngredient,

    // NUMERIC(10,3): több tizedesjegyet a DB csendben kerekítene — itt 400
    @Positive
    @Digits(integer = 7, fraction = 3, message = "The dose must have at most 7 integer and 3 fraction digits")
    BigDecimal dose,

    @Size(max = 255)
    String doseUnit,

    @Size(max = 255)
    String route,

    // Opcionális: az a (jóváhagyott) állítás, amiből a gyógyszer származik
    Long sourceStatementId
)
{}
