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

import bme.mediai.recording.service.RecordingUploadedEvent;

// A feltöltés commitja UTÁN, külön szálon adja át a felvételt a hangszolgáltatásnak. Ha többszöri
// próbálkozásra sem érhető el, a felvétel FAILED (a hang törlődik): PROCESSING-ben nem ragadhat.
@Component
public class RecordingDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(RecordingDispatcher.class);
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(10),
            Duration.ofSeconds(30));

    private final VoiceServiceClient client;
    private final RecordingProcessingService processingService;

    public RecordingDispatcher(VoiceServiceClient client, RecordingProcessingService processingService) {
        this.client = client;
        this.processingService = processingService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUploaded(RecordingUploadedEvent event) {
        Long id = event.job().recordingId();
        for (int attempt = 0; attempt <= RETRY_DELAYS.size(); attempt++) {
            try {
                client.submitRecording(event.job());
                LOG.info("A(z) {} felvétel átadva a hangszolgáltatásnak", id);
                return;
            } catch (RestClientException e) {
                LOG.warn("A hangszolgáltatás nem fogadta a(z) {} felvételt ({}. próbálkozás): {}",
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
        processingService.fail(id, "A hangszolgáltatás (ai-service/voice) nem érhető el, a feldolgozás nem indult el");
    }
}
