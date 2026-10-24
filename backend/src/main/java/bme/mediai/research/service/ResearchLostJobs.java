package bme.mediai.research.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import bme.mediai.processing.service.AiServiceClient;
import bme.mediai.research.persistence.ArticleEvalStatus;
import bme.mediai.research.persistence.RankingStatus;
import bme.mediai.research.persistence.Research;
import bme.mediai.research.persistence.ResearchArticle;
import bme.mediai.research.persistence.ResearchArticleRepository;
import bme.mediai.research.persistence.ResearchContext;
import bme.mediai.research.persistence.ResearchContextRepository;
import bme.mediai.research.persistence.ResearchContextStatus;
import bme.mediai.research.persistence.ResearchRepository;
import bme.mediai.research.persistence.ResearchStatus;

// Beragadt kutatási feladatok (a docproc leállt / újraindult, a sora elveszett, a callback sosem jön —
// ld. ClinicalSummaryService.failLostJobs). Percenként: a 60 mp-nél régebben kért, a Python /health
// "pending" listájában nem szereplő feladat FAILED, így újraindítható (a betegállapot-összefoglalónál a
// "már készül" ellenőrzés sem blokkol tovább). Ha a Python nem érhető el, nála semmi sem fut: mind FAILED.
// Szándékosan NEM tranzakcióban; a lezárás soronként a service tranzakciójában, csak a még futó állapotból.
@Component
public class ResearchLostJobs {

    private static final Logger LOG = LoggerFactory.getLogger(ResearchLostJobs.class);
    private static final Duration LOST_JOB_GRACE = Duration.ofSeconds(60);
    private static final String LOST = "A feldolgozó (ai-service/docproc) leállt vagy újraindult, a kérés elveszett";

    private final ResearchContextRepository contextRepository;
    private final ResearchRepository researchRepository;
    private final ResearchArticleRepository articleRepository;
    private final ResearchContextService contextService;
    private final ResearchService researchService;
    private final AiServiceClient aiService;

    public ResearchLostJobs(ResearchContextRepository contextRepository, ResearchRepository researchRepository,
            ResearchArticleRepository articleRepository, ResearchContextService contextService,
            ResearchService researchService, AiServiceClient aiService) {
        this.contextRepository = contextRepository;
        this.researchRepository = researchRepository;
        this.articleRepository = articleRepository;
        this.contextService = contextService;
        this.researchService = researchService;
        this.aiService = aiService;
    }

    @Scheduled(initialDelay = 20, fixedDelay = 60, timeUnit = TimeUnit.SECONDS)
    public void failLostJobs() {
        OffsetDateTime before = OffsetDateTime.now().minus(LOST_JOB_GRACE);
        List<ResearchContext> contexts = contextRepository.findByStatusAndRequestedAtBefore(
                ResearchContextStatus.PROCESSING, before);
        List<Research> searches = researchRepository.findByStatusAndCreatedAtBefore(ResearchStatus.SEARCHING, before);
        List<Research> rankings = researchRepository.findByRankingStatusAndRankedAtBefore(RankingStatus.PENDING,
                before);
        List<ResearchArticle> evaluations = articleRepository.findByEvalStatusAndEvaluatedAtBefore(
                ArticleEvalStatus.PENDING, before);
        if (contexts.isEmpty() && searches.isEmpty() && rankings.isEmpty() && evaluations.isEmpty()) {
            return;
        }
        // régi Python-verzió (nincs "pending" a /health-ben): nem tudjuk, mi fut nála, ne nyúljunk hozzá
        Optional<Set<String>> pending = aiService.pendingJobs();
        if (pending.isEmpty()) {
            return;
        }
        Set<String> running = pending.get();
        int failed = 0;
        for (ResearchContext c : contexts) {
            if (!running.contains("research-context:" + c.getId())) {
                contextService.fail(c.getId(), LOST + " — készítsd el újra az összefoglalót");
                failed++;
            }
        }
        for (Research r : searches) {
            if (!running.contains("search:" + r.getId())) {
                researchService.fail(r.getId(), LOST + " — indítsd újra a keresést");
                failed++;
            }
        }
        for (Research r : rankings) {
            if (!running.contains("ranking:" + r.getId())) {
                researchService.failRanking(r.getId(), LOST + " — kérd újra a rangsort");
                failed++;
            }
        }
        List<Long> lostArticles = evaluations.stream()
                .map(ResearchArticle::getId)
                .filter(id -> !running.contains("article:" + id))
                .toList();
        if (!lostArticles.isEmpty()) {
            researchService.failEvaluations(lostArticles, LOST + " — kérd újra az értékelést");
            failed += lostArticles.size();
        }
        if (failed > 0) {
            LOG.warn("{} beragadt kutatási feladat lezárva (FAILED)", failed);
        }
    }
}
