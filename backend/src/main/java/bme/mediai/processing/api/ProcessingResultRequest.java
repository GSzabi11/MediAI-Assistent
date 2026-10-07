package bme.mediai.processing.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import bme.mediai.document.api.ChunkBatchRequest;
import bme.mediai.document.persistence.DocumentType;
import bme.mediai.lab.api.LabResultRequest;
import bme.mediai.statement.persistence.Severity;
import bme.mediai.statement.persistence.Uncertainty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

// Python → Spring callback: POST /api/documents/{id}/processing-result (docs/document-processing.md).
// FAILED-nél csak az error kell; PROCESSED-nél a többi mező, a típustól függően.
public record ProcessingResultRequest(

    @NotNull(message = "The status must be PROCESSED or FAILED")
    ProcessingOutcome status,

    @Size(max = 2000)
    String error,

    // LAB | AMBULANCE (a Python determinisztikus kulcsszó-keresése; IMAGING itt nem lehet)
    DocumentType documentType,

    // A PDF szöveges rétege; minden offset (chunk, állítás) erre, Unicode code pointban
    String extractedText,

    // A dokumentum szövegéből kiolvasott dátum, nem a feltöltés ideje
    LocalDate clinicalDate,

    @Valid
    Extraction extraction,

    @Valid
    ChunkBatchRequest chunks,

    // Csak LAB-nál; a nem numerikus sorokat a Python már kihagyta (és a warnings-ba írta)
    List<@Valid LabResultRequest> labResults,

    // Csak AMBULANCE-nál
    List<@Valid ExtractedStatement> statements,

    List<@NotBlank @Size(max = 500) String> warnings
) {

    // HOGY: a kinyerő modell; az állítások producer-e és a dokumentum extraction_* oszlopai
    public record Extraction(
        @NotBlank @Size(max = 128) String modelName,
        @NotNull Long seed,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$", message = "The promptHash must be a sha256 hex") String promptHash,
        @NotBlank @Size(max = 64) String extractorVersion
    ) {
    }

    // Egy ambuláns lapból kinyert adat: MODEL_INFERENCE állítás lesz. A [charStart, charEnd)
    // a bizonyíték-idézet helye, amit a Python determinisztikusan keresett meg a szövegben.
    public record ExtractedStatement(
        @NotBlank String text,
        @Size(max = 128) String label,
        @NotNull Severity severity,
        @NotNull Uncertainty uncertainty,
        @NotNull @PositiveOrZero Integer charStart,
        @NotNull Integer charEnd,
        // Legfeljebb az egyik: ha van, a gyógyszer/allergia listára is felkerül ("nem ellenőrzött")
        @Valid ExtractedMedication medication,
        @Valid ExtractedAllergy allergy
    ) {
    }

    public record ExtractedMedication(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 255) String activeIngredient,
        @Positive
        @Digits(integer = 7, fraction = 3, message = "The dose must have at most 7 integer and 3 fraction digits")
        BigDecimal dose,
        @Size(max = 255) String doseUnit,
        @Size(max = 255) String route
    ) {
    }

    public record ExtractedAllergy(
        @NotBlank @Size(max = 255) String substance
    ) {
    }
}
