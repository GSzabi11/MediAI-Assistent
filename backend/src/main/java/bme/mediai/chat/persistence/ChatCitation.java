package bme.mediai.chat.persistence;

import bme.mediai.statement.persistence.StatementSource;

// Egy pont forrása: a context/retrieved egyik eleme (a felirattal együtt tárolva, hogy a
// válasz akkor is olvasható maradjon, ha a hivatkozott sor később megváltozik/törlődik)
public record ChatCitation(String kind, Long id, String label, StatementSource source) {

    public static ChatCitation of(ChatContextItem item) {
        return new ChatCitation(item.kind(), item.id(), item.label(), item.source());
    }
}
