package bme.mediai.medicalhistory.service;

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

import bme.mediai.medicalhistory.api.MedicalHistoryJobRequest;
import bme.mediai.processing.service.AiServiceClient;

// Commit után, külön szálon adja át a kérést a Python sorának (a dokumentum-feldolgozással közös GPU-sor).
// Ha nem érhető el, FAILED — ne ragadjon PROCESSING-ben.
@Component
public class MedicalHistoryDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(MedicalHistoryDispatcher.class);
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(10));

    private final AiServiceClient client;
    private final MedicalHistoryService service;

    public MedicalHistoryDispatcher(AiServiceClient client, MedicalHistoryService service) {
        this.client = client;
        this.service = service;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(MedicalHistoryRequestedEvent event) {
        String base = "/api/medical-histories/" + event.historyId();
        MedicalHistoryJobRequest job = new MedicalHistoryJobRequest(event.historyId(),
                client.callbackUrl(base + "/result"), client.callbackUrl(base + "/progress"), event.facts());
        for (int attempt = 0; attempt <= RETRY_DELAYS.size(); attempt++) {
            try {
                client.submitMedicalHistory(job);
                return;
            } catch (HttpClientErrorException.NotFound e) {
                LOG.error("Az AI-szolgáltatás nem ismeri a kórtörténet-végpontot (régi verzió?)", e);
                service.fail(event.historyId(), "Az AI-feldolgozó szolgáltatás régi verziójú (nincs kórtörténet-"
                        + "végpontja) — indítsd újra az ai-service/docproc szolgáltatást (python app.py)");
                return;
            } catch (RestClientException e) {
                LOG.warn("Az AI-szolgáltatás nem fogadta a(z) {} kórtörténetet ({}. próbálkozás): {}",
                        event.historyId(), attempt + 1, e.getMessage());
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
        service.fail(event.historyId(), "Az AI-szolgáltatás nem érhető el, a kórtörténet nem készült el");
    }
}
