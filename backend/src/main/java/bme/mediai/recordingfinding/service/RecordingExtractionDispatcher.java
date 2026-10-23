package bme.mediai.recordingfinding.service;

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
import bme.mediai.recording.service.RecordingTranscribedEvent;
import bme.mediai.recordingfinding.exception.RecordingFindingConflictException;

// (1) A felvétel elkészültekor (commit után) automatikusan kéri a csoportok kinyerését. (2) A kérést a
// commit után, külön szálon adja át a hangszolgáltatásnak; ha nem érhető el, FAILED.
@Component
public class RecordingExtractionDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(RecordingExtractionDispatcher.class);
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(10));

    private final VoiceServiceClient client;
    private final RecordingFindingService service;

    public RecordingExtractionDispatcher(VoiceServiceClient client, RecordingFindingService service) {
        this.client = client;
        this.service = service;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTranscribed(RecordingTranscribedEvent event) {
        try {
            service.request(event.recordingId(), null);
        } catch (RecordingFindingConflictException e) {
            LOG.info("A(z) {} felvétel automatikus kinyerése nem indult: {}", event.recordingId(), e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("A(z) {} felvétel automatikus kinyerése nem indult", event.recordingId(), e);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(RecordingExtractionRequestedEvent event) {
        Long id = event.job().extractionId();
        for (int attempt = 0; attempt <= RETRY_DELAYS.size(); attempt++) {
            try {
                client.submitRecordingExtraction(event.job());
                return;
            } catch (RestClientException e) {
                LOG.warn("A hangszolgáltatás nem fogadta a(z) {} kinyerést ({}. próbálkozás): {}",
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
        service.fail(id, "A hangszolgáltatás (ai-service/voice) nem érhető el, a csoportok nem készültek el");
    }
}
