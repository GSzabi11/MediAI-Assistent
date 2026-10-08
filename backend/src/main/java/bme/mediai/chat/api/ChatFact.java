package bme.mediai.chat.api;

import bme.mediai.statement.persistence.StatementSource;

// Spring → Python: egy tény a modell kontextusában. A hivatkozható tényeknél (source != null)
// a modell a sorszámukkal hivatkozik rájuk; a Python a {kind, id} párt adja vissza.
// A háttér-tényeknél (életkor, dokumentumlista, forrás nélküli gyógyszer) kind = BACKGROUND, id = null.
// abnormal: a szabálymotor szerint a referencián kívüli laborérték (a modell ezt mindig megkapja;
// a normál laborokat a Python csak akkor adja részletesen, ha a kérdés említi őket).
public record ChatFact(String kind, Long id, String label, String text, StatementSource source,
        boolean abnormal) {

    public ChatFact(String kind, Long id, String label, String text, StatementSource source) {
        this(kind, id, label, text, source, false);
    }
}
