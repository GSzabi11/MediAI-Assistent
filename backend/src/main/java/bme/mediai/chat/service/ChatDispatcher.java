package bme.mediai.chat.service;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import bme.mediai.processing.service.AiServiceClient;

// Commit után, külön szálon adja át a kérdést a Python sorának (a chat a sor ELEJÉRE kerül,
// a futó feladatot megvárja). Ha a szolgáltatás nem érhető el, FAILED — ne ragadjon.
@Component
public class ChatDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(ChatDispatcher.class);
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(3));

    private final AiServiceClient client;
    private final ChatService service;

    public ChatDispatcher(AiServiceClient client, ChatService service) {
        this.client = client;
        this.service = service;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(ChatRequestedEvent event) {
        for (int attempt = 0; attempt <= RETRY_DELAYS.size(); attempt++) {
            try {
                client.submitChat(event.job());
                return;
            } catch (HttpClientErrorException.NotFound e) {
                // a szolgáltatás fut, de nincs /chats végpontja: régi verzió (újraindítás kell)
                LOG.error("Az AI-szolgáltatás nem ismeri a chat-végpontot (régi verzió?)", e);
                service.fail(event.chatId(), "Az AI-feldolgozó szolgáltatás régi verziójú (nincs chat-végpontja) — "
                        + "indítsd újra az ai-service/docproc szolgáltatást (python app.py)");
                return;
            } catch (RestClientException e) {
                LOG.warn("Az AI-szolgáltatás nem fogadta a(z) {} kérdést ({}. próbálkozás): {}",
                        event.chatId(), attempt + 1, e.getMessage());
                if (attempt < RETRY_DELAYS.size()) {
                    try {
                        Thread.sleep(RETRY_DELAYS.get(attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        service.fail(event.chatId(), "Az AI-szolgáltatás nem érhető el, a válasz nem készült el");
    }
}
