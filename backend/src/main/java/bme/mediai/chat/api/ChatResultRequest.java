package bme.mediai.chat.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Python → Spring callback: POST /api/chat-messages/{id}/result. FAILED-nél csak az error.
// A forrás {kind, id} — a Spring a modellnek átadott tények közül keresi ki (más nem lehet).
public record ChatResultRequest(

    @NotNull @Pattern(regexp = "^(DONE|FAILED)$", message = "The status must be DONE or FAILED")
    String status,

    @Size(max = 2000)
    String error,

    @Size(max = 30)
    List<@Valid @NotNull Point> points,

    List<@NotBlank @Size(max = 500) String> warnings,

    @Valid
    Model model
) {
    public record Point(
        @NotBlank @Size(max = 2000) String text,
        boolean list,
        boolean heading,
        @NotNull @Size(max = 10) List<@Valid @NotNull SourceRef> sources
    ) {
    }

    public record SourceRef(@NotBlank String kind, @NotNull Long id) {
    }

    public record Model(
        @NotBlank @Size(max = 128) String modelName,
        @NotNull Long seed,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String promptHash,
        @NotBlank @Size(max = 64) String extractorVersion
    ) {
    }
}
