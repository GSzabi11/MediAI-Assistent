package bme.mediai.chat.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import bme.mediai.chat.persistence.ChatContextItem;
import bme.mediai.chat.persistence.ChatMessage;
import bme.mediai.chat.persistence.ChatPoint;
import bme.mediai.chat.persistence.ChatStatus;
import bme.mediai.statement.persistence.Severity;
import bme.mediai.statement.persistence.Uncertainty;

public record ChatMessageResponse(
        Long id,
        Long patientId,
        ChatStatus status,
        Long requestedBy,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt,
        String error,
        String question,
        List<Long> history,
        // a válasz pontjai a forrásaikkal (PROCESSING/FAILED: üres)
        List<ChatPoint> answer,
        // forrás-lefedettségből számolva, nem a modell önértékelése
        Uncertainty uncertainty,
        // pl. a válaszban olyan szám, ami a kontextusban nem szerepel — a felületnek ki kell írnia
        List<String> warnings,
        // a hivatkozott laborsorokhoz tartozó (még érvényes) szabály-állítások
        List<RelatedRule> relatedRules,
        // HOGY: melyik modell, seed, prompt, embedding
        Model model,
        // mennyi tényt kapott a modell, fajtánként (MIBŐL)
        Map<String, Integer> contextCounts
) {
    public record RelatedRule(Long statementId, String text, Severity severity) {
    }

    public record Model(String modelName, Long seed, String promptHash, String extractorVersion,
            String embeddingModel) {
    }

    public static ChatMessageResponse from(ChatMessage m, List<RelatedRule> relatedRules) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ChatContextItem item : m.getContext()) {
            counts.merge(item.kind(), 1, Integer::sum);
        }
        for (ChatContextItem item : m.getRetrieved()) {
            counts.merge(item.kind(), 1, Integer::sum);
        }
        return new ChatMessageResponse(
                m.getId(), m.getPatientId(), m.getStatus(), m.getRequestedBy(), m.getRequestedAt(),
                m.getCompletedAt(), m.getError(), m.getQuestion(), m.getHistory(),
                m.getAnswer() == null ? List.of() : m.getAnswer(), m.getUncertainty(), m.getWarnings(),
                relatedRules,
                m.getModelName() == null ? null : new Model(m.getModelName(), m.getSeed(), m.getPromptHash(),
                        m.getExtractorVersion(), m.getEmbeddingModel()),
                counts);
    }
}
