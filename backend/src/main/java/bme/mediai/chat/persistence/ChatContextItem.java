package bme.mediai.chat.persistence;

import bme.mediai.statement.persistence.StatementSource;

// Egy hivatkozható tény, amit a modell megkapott (chat_message.context / retrieved eleme).
// kind: LAB_RESULT | STATEMENT | MEDICATION | ALLERGY | CHUNK; id: az adott tábla sora.
// A label a felületen a forrás-chip felirata — a Spring adja, nem a modell.
public record ChatContextItem(String kind, Long id, String label, StatementSource source) {
}
