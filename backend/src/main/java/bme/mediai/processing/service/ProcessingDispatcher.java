package bme.mediai.processing.service;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClientException;

import bme.mediai.document.service.DocumentUploadedEvent;

// A feltöltés commitja UTÁN, külön szálon adja át a PDF-et a Pythonnak: a feltöltő kérés
// addigra 202-vel visszatért. Ha a Python többszöri próbálkozásra sem érhető el, a dokumentum
// FAILED lesz — PROCESSING-ben nem ragadhat úgy, hogy senki sem dolgozik rajta.
@Component
public class ProcessingDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessingDispatcher.class);
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(10),
            Duration.ofSeconds(30));

    private final AiServiceClient client;
    private final DocumentProcessingService processingService;

    public ProcessingDispatcher(AiServiceClient client, DocumentProcessingService processingService) {
        this.client = client;
        this.processingService = processingService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUploaded(DocumentUploadedEvent event) {
        RestClientException last = null;
        for (int attempt = 0; attempt <= RETRY_DELAYS.size(); attempt++) {
            try {
                client.submit(event.documentId(), event.filePath().toString());
                LOG.info("A(z) {} dokumentum átadva az AI-szolgáltatásnak", event.documentId());
                return;
            } catch (RestClientException e) {
                last = e;
                LOG.warn("Az AI-szolgáltatás nem fogadta a(z) {} dokumentumot ({}. próbálkozás): {}",
                        event.documentId(), attempt + 1, e.getMessage());
                if (attempt < RETRY_DELAYS.size() && !sleep(RETRY_DELAYS.get(attempt))) {
                    break;
                }
            }
        }
        LOG.error("A(z) {} dokumentum feldolgozása nem indítható", event.documentId(), last);
        processingService.fail(event.documentId(),
                "Az AI-feldolgozó szolgáltatás nem érhető el, a feldolgozás nem indult el");
    }

    private static boolean sleep(Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
