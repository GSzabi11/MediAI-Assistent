package bme.mediai.chat.persistence;

import java.util.List;

// A válasz egy pontja (chat_message.answer eleme). list: számozott/felsorolt pont volt;
// heading: bevezető/címsor (pl. "A lelet a következőket mutatja:") — ennek nem kell forrás.
public record ChatPoint(String text, boolean list, boolean heading, List<ChatCitation> sources) {
}
