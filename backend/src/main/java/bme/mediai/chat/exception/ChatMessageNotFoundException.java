package bme.mediai.chat.exception;

public class ChatMessageNotFoundException extends RuntimeException {

    public ChatMessageNotFoundException(Long id) {
        super("Nem található chat-üzenet ezzel az azonosítóval: " + id);
    }
}
