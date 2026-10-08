package bme.mediai.chat.exception;

// Már készül válasz a betegnek, vagy az eredmény már megérkezett
public class ChatConflictException extends RuntimeException {

    public ChatConflictException(String message) {
        super(message);
    }
}
