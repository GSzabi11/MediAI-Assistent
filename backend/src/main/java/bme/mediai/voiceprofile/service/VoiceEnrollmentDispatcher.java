package bme.mediai.voiceprofile.service;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClientException;

import bme.mediai.processing.service.VoiceServiceClient;

// Commit után, külön szálon adja át a hangprofilt a Python hangszolgáltatásnak. Ha nem érhető el,
// FAILED — ne ragadjon PROCESSING-ben (akkor új profil sem vehetne fel az orvos).
@Component
public class VoiceEnrollmentDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(VoiceEnrollmentDispatcher.class);
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(10));

    private final VoiceServiceClient client;
    private final VoiceProfileService service;

    public VoiceEnrollmentDispatcher(VoiceServiceClient client, VoiceProfileService service) {
        this.client = client;
        this.service = service;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(VoiceEnrollmentRequestedEvent event) {
        Long id = event.job().profileId();
        for (int attempt = 0; attempt <= RETRY_DELAYS.size(); attempt++) {
            try {
                client.submitEnrollment(event.job());
                LOG.info("A(z) {} hangprofil átadva a hangszolgáltatásnak", id);
                return;
            } catch (RestClientException e) {
                LOG.warn("A hangszolgáltatás nem fogadta a(z) {} hangprofilt ({}. próbálkozás): {}",
                        id, attempt + 1, e.getMessage());
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
        service.failEnrollment(id, "A hangszolgáltatás (ai-service/voice) nem érhető el, a hangprofil nem készült el");
    }
}
