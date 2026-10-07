package bme.mediai.statement.persistence;

import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// MERRE: a statement.source JSONB oszlop tartalma. A "type" mezőt a DB CHECK is ellenőrzi.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StatementSource.Audio.class, name = "AUDIO"),
        @JsonSubTypes.Type(value = StatementSource.Document.class, name = "DOCUMENT"),
        @JsonSubTypes.Type(value = StatementSource.Image.class, name = "IMAGE"),
        @JsonSubTypes.Type(value = StatementSource.Lab.class, name = "LAB")
})
public sealed interface StatementSource {

    void validate();

    record Audio(Long recordingId, List<Integer> turnIds) implements StatementSource {
        @Override
        public void validate() {
            require(recordingId != null, "Az AUDIO forráshoz kell recordingId");
            require(turnIds != null && !turnIds.isEmpty(), "Az AUDIO forráshoz kell legalább egy turnId");
            require(turnIds.stream().allMatch(t -> t != null && t >= 0), "A turnId-k nem negatív egészek");
            require(new HashSet<>(turnIds).size() == turnIds.size(), "Ismétlődő turnId a forrásban");
        }
    }

    // Offszetek Unicode code pointban; a dokumentum létezését, betegét és a tartományt
    // a StatementService ellenőrzi a kinyert szövegen.
    record Document(Long documentId, Integer charStart, Integer charEnd) implements StatementSource {
        @Override
        public void validate() {
            require(documentId != null, "A DOCUMENT forráshoz kell documentId");
            require(charStart != null && charEnd != null, "A DOCUMENT forráshoz kell charStart és charEnd");
            require(charStart >= 0 && charEnd > charStart, "Érvénytelen karaktertartomány");
        }
    }

    // A bbox a lap méretéhez normált (0–1), így felbontásfüggetlen.
    record Image(Long documentId, Integer page, Double x, Double y, Double width, Double height)
            implements StatementSource {
        @Override
        public void validate() {
            require(documentId != null, "Az IMAGE forráshoz kell documentId");
            require(page != null && page >= 1, "Az IMAGE forrás page értéke legalább 1");
            require(x != null && y != null && width != null && height != null,
                    "Az IMAGE forráshoz kell x, y, width, height");
            require(x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= 1 && y + height <= 1,
                    "A bbox a lapon belül legyen (normált 0–1 koordináták)");
        }
    }

    // Egy laborsor (mérés) — csak a szabálymotor állításai hivatkozhatnak így (DB CHECK is)
    record Lab(Long labResultId) implements StatementSource {
        @Override
        public void validate() {
            require(labResultId != null, "A LAB forráshoz kell labResultId");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
