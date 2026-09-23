package bme.mediai.statement.persistence;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// HOGY: a statement.producer JSONB oszlop tartalma. Melyik fajta melyik StatementType-hoz
// tartozik, azt a Statement entitás és a DB CHECK (producer_matches_type) is kikényszeríti.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StatementProducer.Rule.class, name = "RULE"),
        @JsonSubTypes.Type(value = StatementProducer.Model.class, name = "MODEL"),
        @JsonSubTypes.Type(value = StatementProducer.Extractor.class, name = "EXTRACTOR")
})
public sealed interface StatementProducer {

    void validate();

    // Az ASR-modellt a service tölti ki a forrás-felvételből (voice-module.md), nem a kliens.
    StatementProducer withAsrModel(String asrModel);

    record Rule(String ruleId, String ruleVersion) implements StatementProducer {
        @Override
        public void validate() {
            require(notBlank(ruleId) && notBlank(ruleVersion), "A RULE előállítóhoz kell ruleId és ruleVersion");
        }

        @Override
        public StatementProducer withAsrModel(String asrModel) {
            return this;
        }
    }

    record Model(String modelName, Long seed, String promptHash, String asrModel) implements StatementProducer {
        @Override
        public void validate() {
            require(notBlank(modelName) && seed != null && notBlank(promptHash),
                    "A MODEL előállítóhoz kell modelName, seed és promptHash");
        }

        @Override
        public StatementProducer withAsrModel(String asrModel) {
            return new Model(modelName, seed, promptHash, asrModel);
        }
    }

    record Extractor(String extractorName, String extractorVersion, String asrModel) implements StatementProducer {
        @Override
        public void validate() {
            require(notBlank(extractorName) && notBlank(extractorVersion),
                    "Az EXTRACTOR előállítóhoz kell extractorName és extractorVersion");
        }

        @Override
        public StatementProducer withAsrModel(String asrModel) {
            return new Extractor(extractorName, extractorVersion, asrModel);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
