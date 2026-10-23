package bme.mediai.epicrisis.service;

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

import bme.mediai.epicrisis.api.EpicrisisJobRequest;
import bme.mediai.processing.service.AiServiceClient;

// Commit után, külön szálon adja át a kérést a Python GPU-sorának. Ha nem érhető el, FAILED — ne ragadjon
// PROCESSING-ben (mint a MedicalHistoryDispatcher).
@Component
public class EpicrisisDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(EpicrisisDispatcher.class);
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(10));

    private final AiServiceClient client;
    private final EpicrisisService service;

    public EpicrisisDispatcher(AiServiceClient client, EpicrisisService service) {
        this.client = client;
        this.service = service;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(EpicrisisRequestedEvent event) {
        String base = "/api/epicrises/" + event.epicrisisId();
        EpicrisisJobRequest job = new EpicrisisJobRequest(event.epicrisisId(), client.callbackUrl(base + "/result"),
                client.callbackUrl(base + "/progress"), event.facts());
        for (int attempt = 0; attempt <= RETRY_DELAYS.size(); attempt++) {
            try {
                client.submitEpicrisis(job);
                return;
            } catch (HttpClientErrorException.NotFound e) {
                LOG.error("Az AI-szolgáltatás nem ismeri az epikrízis-végpontot (régi verzió?)", e);
                service.fail(event.epicrisisId(), "Az AI-feldolgozó szolgáltatás régi verziójú (nincs epikrízis-"
                        + "végpontja) — indítsd újra az ai-service/docproc szolgáltatást (python app.py)");
                return;
            } catch (RestClientException e) {
                LOG.warn("Az AI-szolgáltatás nem fogadta a(z) {} epikrízist ({}. próbálkozás): {}",
                        event.epicrisisId(), attempt + 1, e.getMessage());
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
        service.fail(event.epicrisisId(), "Az AI-szolgáltatás nem érhető el, az epikrízis nem készült el");
    }
}
