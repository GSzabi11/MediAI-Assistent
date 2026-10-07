package bme.mediai.summary.service;

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

// Commit után, külön szálon adja át a kérést a Python sorának (ugyanaz a sor, mint a
// dokumentum-feldolgozásé: egyszerre egy GPU-feladat). Ha nem érhető el, FAILED — ne ragadjon.
@Component
public class SummaryDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(SummaryDispatcher.class);
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(10));

    private final AiServiceClient client;
    private final ClinicalSummaryService service;

    public SummaryDispatcher(AiServiceClient client, ClinicalSummaryService service) {
        this.client = client;
        this.service = service;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(SummaryRequestedEvent event) {
        for (int attempt = 0; attempt <= RETRY_DELAYS.size(); attempt++) {
            try {
                client.submitSummary(event.summaryId(), event.facts());
                return;
            } catch (HttpClientErrorException.NotFound e) {
                // a szolgáltatás fut, de nincs /summaries végpontja: régi verzió (újraindítás kell)
                LOG.error("Az AI-szolgáltatás nem ismeri az összefoglaló-végpontot (régi verzió?)", e);
                service.fail(event.summaryId(), "Az AI-feldolgozó szolgáltatás régi verziójú (nincs összefoglaló-"
                        + "végpontja) — indítsd újra az ai-service/docproc szolgáltatást (python app.py)");
                return;
            } catch (RestClientException e) {
                LOG.warn("Az AI-szolgáltatás nem fogadta a(z) {} összefoglalót ({}. próbálkozás): {}",
                        event.summaryId(), attempt + 1, e.getMessage());
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
        service.fail(event.summaryId(), "Az AI-szolgáltatás nem érhető el, az összefoglaló nem készült el");
    }
}
