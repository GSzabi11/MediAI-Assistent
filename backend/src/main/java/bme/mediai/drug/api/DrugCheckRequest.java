package bme.mediai.drug.api;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

// Az orvos által beírt gyógyszernevek (márkanév vagy hatóanyag), legfeljebb 5
public record DrugCheckRequest(
        @NotEmpty @Size(max = 5) List<@NotBlank @Size(max = 255) String> drugs
) {
}
