package bme.mediai.lab.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// A @Digits a NUMERIC(12,4) oszlophoz illik: több tizedesjegyet a DB csendben
// kerekítene, ezt itt 400-zal elutasítjuk (CLAUDE.md: nem kerekítünk csendben).
public record LabResultRequest(

    @Size(max = 128)
    String groupName,

    @NotBlank(message = "The name must not be blank")
    @Size(max = 255)
    String name,

    // Számérték; kvalitatív eredménynél null, helyette valueText (pontosan az egyik kell)
    @Digits(integer = 8, fraction = 4, message = "The value must have at most 8 integer and 4 fraction digits")
    BigDecimal value,

    // Cenzorált eredmény (">90" → value 90, comparator ">"); pontos értéknél null
    @Pattern(regexp = "^(<|<=|>|>=)$", message = "The comparator must be one of <, <=, >, >=")
    String comparator,

    // Kvalitatív eredmény szó szerint ("Negatív", "kevés"); számértéknél null
    @Size(max = 64)
    String valueText,

    @Size(max = 32)
    String unit,

    @Digits(integer = 8, fraction = 4, message = "The refLow must have at most 8 integer and 4 fraction digits")
    BigDecimal refLow,

    @Digits(integer = 8, fraction = 4, message = "The refHigh must have at most 8 integer and 4 fraction digits")
    BigDecimal refHigh,

    // Szöveges referencia ("Negatív", "normális")
    @Size(max = 128)
    String refText,

    // Ha hiányzik: a dokumentum klinikai dátuma (budapesti éjfél); ha az sincs, kötelező
    OffsetDateTime measuredAt,

    @Size(max = 64)
    String code,

    // A dokumentum-feldolgozás adja: az eredmény sora a lelet PDF-jében; kézi rögzítésnél null
    @Valid
    LabSourceBox sourceBox
)
{}
