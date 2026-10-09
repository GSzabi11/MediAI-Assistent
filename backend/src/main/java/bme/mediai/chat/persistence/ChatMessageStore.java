package bme.mediai.chat.persistence;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;

import org.springframework.stereotype.Component;

// A chat üzenetei csak memóriában élnek (V25: a felhasználó döntése, nincs chat_message tábla).
// Addig kellenek, amíg a Python visszahív (retrieve, result), a felület lekérdezi a kész választ,
// és amíg a következő kérdés előzményként hivatkozik rájuk — utána eltűnnek. Újraindításkor elvesznek.
@Component
public class ChatMessageStore {

    // ennyi ideig tartjuk meg a kérdést (a beteg adatait is hordozza — ne tovább, mint kell);
    // a félbemaradt (PROCESSING) is ennyi után szabadítja fel a beteget
    static final Duration RETENTION = Duration.ofHours(2);

    // Az azonosító az indulás idejéből (ms) indul: újraindítás után egy új kérdés sem kaphatja egy
    // régi, még futó Python-feladat azonosítóját, így az elkésett callback 404-et kap, nem ír bele.
    private final AtomicLong nextId = new AtomicLong(System.currentTimeMillis());
    private final Map<Long, ChatMessage> messages = new ConcurrentHashMap<>();

    // Betegenként egyszerre legfeljebb egy készülő válasz: az ellenőrzés és a felvétel együtt,
    // szinkronizáltan fut (korábban a részleges egyedi index fogta meg a párhuzamos kérést).
    public synchronized Optional<ChatMessage> addIfIdle(Long patientId, LongFunction<ChatMessage> factory) {
        purgeExpired();
        boolean busy = messages.values().stream()
                .anyMatch(m -> m.getPatientId().equals(patientId) && m.getStatus() == ChatStatus.PROCESSING);
        if (busy) {
            return Optional.empty();
        }
        ChatMessage message = factory.apply(nextId.incrementAndGet());
        messages.put(message.getId(), message);
        return Optional.of(message);
    }

    public Optional<ChatMessage> findById(Long id) {
        purgeExpired();
        return Optional.ofNullable(messages.get(id));
    }

    // a megadott azonosítók közül a még meglévők (a lejártak kimaradnak)
    public List<ChatMessage> findAllById(Iterable<Long> ids) {
        purgeExpired();
        List<ChatMessage> found = new ArrayList<>();
        for (Long id : ids) {
            ChatMessage m = messages.get(id);
            if (m != null) {
                found.add(m);
            }
        }
        return found;
    }

    private void purgeExpired() {
        OffsetDateTime limit = OffsetDateTime.now().minus(RETENTION);
        messages.values().removeIf(m -> m.getRequestedAt().isBefore(limit));
    }
}
