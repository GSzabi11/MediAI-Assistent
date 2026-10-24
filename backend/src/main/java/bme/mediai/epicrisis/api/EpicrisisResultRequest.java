package bme.mediai.epicrisis.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import bme.mediai.medicalhistory.api.MedicalHistoryResultRequest;
import bme.mediai.medicalhistory.api.MedicalHistoryResultRequest.Digest;

// Python → Spring callback: POST /api/epicrises/{id}/result. FAILED-nél csak az error.
// A dokumentum-kivonat és a modell leírása ugyanaz, mint a kórtörténetnél (közös document_digest).
public record EpicrisisResultRequest(

    @NotNull @Pattern(regexp = "^(DONE|FAILED)$", message = "The status must be DONE or FAILED")
    String status,

    @Size(max = 2000)
    String error,

    @Size(max = 100)
    List<@Valid Diagnosis> diagnoses,

    // a modell legfeljebb 6-6 pontja + a felvételek csoportjaiból determinisztikusan pótolt elemek
    @Size(max = 60)
    List<@Valid Finding> findings,

    @Size(max = 60)
    List<@NotBlank @Size(max = 500) String> recommendations,

    List<@NotBlank @Size(max = 500) String> warnings,

    @Size(max = 100)
    List<@Valid Digest> digests,

    @Valid
    MedicalHistoryResultRequest.Model model
) {
    public record Diagnosis(
        @NotBlank @Size(max = 300) String name,
        @Pattern(regexp = MedicalHistoryResultRequest.BNO) String bnoCode,
        Long documentId,
        Long recordingId,
        @Size(max = 50) List<@NotNull Integer> turnIds
    ) {
    }

    public record Finding(
        @NotBlank @Size(max = 1000) String text,
        Long documentId,
        Long recordingId,
        @Size(max = 50) List<@NotNull Integer> turnIds,
        @Size(max = 1000) String quote,
        @Size(max = 500) String flag
    ) {
    }
}
