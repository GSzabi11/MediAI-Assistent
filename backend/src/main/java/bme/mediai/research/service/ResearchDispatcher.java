package bme.mediai.research.service;

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
import bme.mediai.research.api.ResearchEvaluationJobRequest;

// Commit után, külön szálon adja át a kéréseket a Pythonnak. Ha nem érhető el, FAILED — ne ragadjon.
@Component
public class ResearchDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(ResearchDispatcher.class);
    private static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(2), Duration.ofSeconds(10));
    private static final String OLD_VERSION = "Az AI-feldolgozó szolgáltatás régi verziójú (nincs kutatás-végpontja)"
            + " — indítsd újra az ai-service/docproc szolgáltatást (python app.py)";
    private static final String UNAVAILABLE = "Az AI-szolgáltatás nem érhető el";

    private final AiServiceClient client;
    private final ResearchContextService contextService;
    private final ResearchService researchService;

    public ResearchDispatcher(AiServiceClient client, ResearchContextService contextService,
            ResearchService researchService) {
        this.client = client;
        this.contextService = contextService;
        this.researchService = researchService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContextRequested(ResearchEvents.ContextRequested event) {
        String error = send(() -> client.submitResearchContext(event.contextId(), event.facts()),
                "kutatási összefoglaló " + event.contextId());
        if (error != null) {
            contextService.fail(event.contextId(), error);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSearchRequested(ResearchEvents.SearchRequested event) {
        String error = send(() -> client.submitResearchSearch(event.job()), "keresés " + event.job().researchId());
        if (error != null) {
            researchService.fail(event.job().researchId(), error);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEvaluationRequested(ResearchEvents.EvaluationRequested event) {
        String error = send(() -> client.submitResearchEvaluation(event.job()),
                "cikk-értékelés (kutatás " + event.job().researchId() + ")");
        if (error != null) {
            researchService.failEvaluations(event.job().articles().stream()
                    .map(ResearchEvaluationJobRequest.Article::id).toList(), error);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRankingRequested(ResearchEvents.RankingRequested event) {
        String error = send(() -> client.submitResearchRanking(event.job()),
                "AI-rangsor (kutatás " + event.job().researchId() + ")");
        if (error != null) {
            researchService.failRanking(event.job().researchId(), error);
        }
    }

    // null = átadva; különben a hiba szövege
    private String send(Runnable call, String label) {
        for (int attempt = 0; attempt <= RETRY_DELAYS.size(); attempt++) {
            try {
                call.run();
                return null;
            } catch (HttpClientErrorException.NotFound e) {
                LOG.error("Az AI-szolgáltatás nem ismeri a kutatás-végpontot (régi verzió?)", e);
                return OLD_VERSION;
            } catch (RestClientException e) {
                LOG.warn("Az AI-szolgáltatás nem fogadta: {} ({}. próbálkozás): {}", label, attempt + 1, e.getMessage());
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
        return UNAVAILABLE;
    }
}
