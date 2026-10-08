package bme.mediai.chat.api;

import java.util.List;

// Spring → Python: a kérdéshez legközelebbi dokumentum-darabok (kind = CHUNK), a legközelebbi elöl
public record ChatRetrieveResponse(List<ChatFact> chunks) {
}
