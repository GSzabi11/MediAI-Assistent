package bme.mediai.chat.api;

import java.util.List;

// Spring → Python: POST {ai-service}/chats (docs/clinical-chat.md). A Python a kérdést beágyazza,
// a retrieveUrl-en lekéri a kérdéshez illő dokumentum-darabokat, majd a callbackUrl-re válaszol.
public record ChatJobRequest(
        Long chatId,
        String callbackUrl,
        String retrieveUrl,
        String question,
        // a korábbi kérdés-válaszok, időrendben (legfeljebb 3)
        List<HistoryTurn> history,
        List<ChatFact> facts
) {
    public record HistoryTurn(String question, String answer) {
    }
}
