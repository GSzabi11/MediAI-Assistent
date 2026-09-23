package bme.mediai.lab.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// A @Digits a NUMERIC(12,4) oszlophoz illik: több tizedesjegyet a DB csendben
// kerekítene, ezt itt 400-zal elutasítjuk (CLAUDE.md: nem kerekítünk csendben).
public record LabResultRequest(

    @Size(max = 128)
    String groupName,

    @NotBlank(message = "The name must not be blank")
    @Size(max = 255)
    String name,

    @NotNull(message = "The value must not be null (censored/qualitative results are rejected)")
    @Digits(integer = 8, fraction = 4, message = "The value must have at most 8 integer and 4 fraction digits")
    BigDecimal value,

    @Size(max = 32)
    String unit,

    @Digits(integer = 8, fraction = 4, message = "The refLow must have at most 8 integer and 4 fraction digits")
    BigDecimal refLow,

    @Digits(integer = 8, fraction = 4, message = "The refHigh must have at most 8 integer and 4 fraction digits")
    BigDecimal refHigh,

    // Ha hiányzik: a dokumentum klinikai dátuma (budapesti éjfél); ha az sincs, kötelező
    OffsetDateTime measuredAt,

    @Size(max = 64)
    String code
)
{}
