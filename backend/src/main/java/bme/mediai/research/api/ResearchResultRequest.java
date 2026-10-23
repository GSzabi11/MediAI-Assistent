package bme.mediai.research.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import bme.mediai.research.persistence.ResearchSource;

// Python → Spring callback: POST /api/research/{id}/result — a talált cikkek rangsorolva.
public record ResearchResultRequest(

    @NotNull @Pattern(regexp = "^(DONE|FAILED)$", message = "The status must be DONE or FAILED")
    String status,

    @Size(max = 2000)
    String error,

    @Min(0)
    Integer totalFound,

    @Size(max = 200)
    List<@Valid @NotNull FoundArticle> articles
) {
    public record FoundArticle(
        @NotNull @Min(1) Integer rank,
        @NotBlank @Pattern(regexp = "^[0-9]{1,16}$") String pmid,
        @Size(max = 255) String doi,
        @NotBlank @Size(max = 2000) String title,
        @NotNull @Size(max = 50) List<@NotBlank @Size(max = 300) String> authors,
        @Size(max = 500) String journal,
        Integer pubYear,
        // a DB oszlop "abstract" (Java-ban foglalt szó, ezért itt abstractText)
        @Size(max = 20000) String abstractText,
        @NotEmpty List<@NotNull ResearchSource> sources,
        @Size(max = 30) List<@NotBlank @Size(max = 200) String> publicationTypes,
        @Min(0) Integer citationCount
    ) {
    }
}
