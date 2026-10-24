package bme.mediai.medicalhistory.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Python → Spring callback: POST /api/medical-histories/{id}/result. FAILED-nél csak az error.
// A dátum év ("2010") vagy ÉÉÉÉ-HH-NN; a BNO-kód a leletből jön, a Python ellenőrizte.
public record MedicalHistoryResultRequest(

    @NotNull @Pattern(regexp = "^(DONE|FAILED)$", message = "The status must be DONE or FAILED")
    String status,

    @Size(max = 2000)
    String error,

    @Size(max = 8000)
    String summary,

    @Size(max = 300)
    List<@Valid TimelineItem> timeline,

    @Size(max = 100)
    List<@Valid Diagnosis> diagnoses,

    @Size(max = 10)
    List<@NotBlank @Size(max = 500) String> recommendations,

    List<@NotBlank @Size(max = 500) String> warnings,

    // az újonnan készült dokumentum-kivonatok (a változatlanok nem jönnek vissza)
    @Size(max = 300)
    List<@Valid Digest> digests,

    @Valid
    Model model
) {
    public static final String DATE = "^\\d{4}(-\\d{2}-\\d{2})?$";
    // a magyar írásmód "/3"-szerű minősítő utótagjával is (H1010/3)
    public static final String BNO = "^[A-Z]\\d{2}(\\.?[0-9A-Z]{1,2})?(/\\d)?$";

    public record TimelineItem(
        @NotBlank @Pattern(regexp = DATE) String date,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 1500) String text,
        @Size(max = 300) String place,
        @NotNull Long documentId,
        @NotNull @Pattern(regexp = "^(LAB|DOCUMENT|HISTORY)$") String kind,
        @Size(max = 500) String flag
    ) {
    }

    public record Diagnosis(
        @NotBlank @Size(max = 300) String name,
        @Pattern(regexp = BNO) String bnoCode,
        @Pattern(regexp = DATE) String date,
        @NotNull Long documentId
    ) {
    }

    public record Digest(
        @NotNull Long documentId,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 300) String place,
        @NotBlank @Size(max = 2000) String summary,
        @Size(max = 2000) String quote,
        @NotNull @Size(max = 30) List<@Valid Event> events,
        @NotNull @Size(max = 30) List<@Valid DigestDiagnosis> diagnoses,
        @NotNull List<@NotBlank @Size(max = 500) String> warnings,
        @NotNull @Valid Model model
    ) {
    }

    public record Event(
        @NotBlank @Pattern(regexp = DATE) String date,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 1000) String quote
    ) {
    }

    public record DigestDiagnosis(
        @NotBlank @Size(max = 300) String name,
        @Pattern(regexp = BNO) String bnoCode,
        @Pattern(regexp = DATE) String since,
        @NotBlank @Size(max = 1000) String quote
    ) {
    }

    public record Model(
        @NotBlank @Size(max = 128) String modelName,
        @NotNull Long seed,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String promptHash,
        @NotBlank @Size(max = 64) String extractorVersion
    ) {
    }
}
