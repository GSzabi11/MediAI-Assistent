package bme.mediai.research.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.Patient;
import bme.mediai.patient.persistence.PatientRepository;
import bme.mediai.processing.service.AiServiceClient;
import bme.mediai.research.api.ArticleEvaluationResultRequest;
import bme.mediai.research.api.ArticlePageResponse;
import bme.mediai.research.api.BookmarkResponse;
import bme.mediai.research.api.ResearchArticleResponse;
import bme.mediai.research.api.ResearchCreateRequest;
import bme.mediai.research.api.ResearchEvaluationJobRequest;
import bme.mediai.research.api.ResearchResponse;
import bme.mediai.research.api.ResearchResultRequest;
import bme.mediai.research.api.ResearchSearchJobRequest;
import bme.mediai.research.exception.ResearchConflictException;
import bme.mediai.research.exception.ResearchNotFoundException;
import bme.mediai.research.persistence.ArticleEvalStatus;
import bme.mediai.research.persistence.Research;
import bme.mediai.research.persistence.ResearchArticle;
import bme.mediai.research.persistence.ResearchArticleRepository;
import bme.mediai.research.persistence.ResearchContextRepository;
import bme.mediai.research.persistence.ResearchRepository;
import bme.mediai.research.persistence.ResearchStatus;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.UserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

// Szakirodalmi kutatás (docs/literature-research.md): a keresést és az értékelést a Python végzi,
// a Spring tárol, lapoz, és a hálózatra menő kifejezést a kapun (OutboundQueryGate) engedi át.
@Service
public class ResearchService {

    public static final int PAGE_SIZE = 10;
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_ERROR_LENGTH = 2000;
    // ennyi idő után a PENDING értékelés beragadtnak számít (pl. a Python újraindult) — újrakérhető
    private static final int STALE_PENDING_MINUTES = 15;

    public enum ArticleFilter { ACTIVE, BOOKMARKED, EXCLUDED, ALL }

    private final ResearchRepository repository;
    private final ResearchArticleRepository articleRepository;
    private final ResearchContextRepository contextRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final OutboundQueryGate gate;
    private final AiServiceClient client;
    private final ApplicationEventPublisher events;
    private final Validator validator;

    public ResearchService(ResearchRepository repository, ResearchArticleRepository articleRepository,
            ResearchContextRepository contextRepository, PatientRepository patientRepository,
            UserRepository userRepository, OutboundQueryGate gate, AiServiceClient client,
            ApplicationEventPublisher events, Validator validator) {
        this.repository = repository;
        this.articleRepository = articleRepository;
        this.contextRepository = contextRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.gate = gate;
        this.client = client;
        this.events = events;
        this.validator = validator;
    }

    @Transactional
    public ResearchResponse create(ResearchCreateRequest request) {
        Patient patient = patientRepository.findById(request.patientId())
                .orElseThrow(() -> new PatientNotFoundException(request.patientId()));
        if (request.userId() != null && !userRepository.existsById(request.userId())) {
            throw new UserNotFoundException(request.userId());
        }
        if (request.contextId() != null && contextRepository.findById(request.contextId())
                .filter(c -> c.getPatientId().equals(patient.getId())).isEmpty()) {
            throw new ResearchNotFoundException("kutatási összefoglaló ennél a betegnél", request.contextId());
        }
        String query = request.query().strip();
        gate.check(query, patient);
        String extra = request.extraPrompt() == null || request.extraPrompt().isBlank() ? null
                : request.extraPrompt().strip();
        int max = request.maxResults() == null ? DEFAULT_MAX_RESULTS : request.maxResults();
        Research saved = repository.saveAndFlush(new Research(patient.getId(), request.contextId(), request.userId(),
                request.contextText().strip(), extra, query, request.sources().stream().distinct().toList(), max));
        String base = "/api/research/" + saved.getId();
        events.publishEvent(new ResearchEvents.SearchRequested(new ResearchSearchJobRequest(saved.getId(),
                client.callbackUrl(base + "/result"), client.callbackUrl(base + "/progress"), query,
                saved.getSources(), max)));
        return response(reload(saved.getId()));
    }

    @Transactional(readOnly = true)
    public List<ResearchResponse> findByPatient(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
        List<Research> all = repository.findByPatientIdOrderByCreatedAtDescIdDesc(patientId);
        Map<Long, ResearchResponse.Counts> counts = counts(all.stream().map(Research::getId).toList());
        return all.stream()
                .map(r -> ResearchResponse.from(r, counts.getOrDefault(r.getId(), ResearchResponse.Counts.EMPTY)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ResearchResponse findById(Long id) {
        return response(reload(id));
    }

    @Transactional(readOnly = true)
    public ArticlePageResponse articles(Long researchId, ArticleFilter filter, int page) {
        reload(researchId);
        Pageable pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by("rank"));
        Page<ResearchArticle> result = switch (filter) {
            case ACTIVE -> articleRepository.findByResearchIdAndExcluded(researchId, false, pageable);
            case EXCLUDED -> articleRepository.findByResearchIdAndExcluded(researchId, true, pageable);
            case BOOKMARKED -> articleRepository.findByResearchIdAndBookmarkedTrue(researchId, pageable);
            case ALL -> articleRepository.findByResearchId(researchId, pageable);
        };
        return new ArticlePageResponse(result.getContent().stream().map(ResearchArticleResponse::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public ResearchArticleResponse updateArticle(Long researchId, Long articleId, Boolean excluded,
            Boolean bookmarked) {
        ResearchArticle article = article(researchId, articleId);
        if (excluded != null) {
            article.setExcluded(excluded);
        }
        if (bookmarked != null) {
            article.setBookmarked(bookmarked);
        }
        return ResearchArticleResponse.from(article);
    }

    @Transactional(readOnly = true)
    public List<BookmarkResponse> bookmarks(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
        List<ResearchArticle> articles = articleRepository.findBookmarkedByPatient(patientId);
        Map<Long, Research> researches = repository.findAllById(
                        articles.stream().map(ResearchArticle::getResearchId).distinct().toList()).stream()
                .collect(Collectors.toMap(Research::getId, Function.identity()));
        return articles.stream().map(a -> {
            Research r = researches.get(a.getResearchId());
            return new BookmarkResponse(ResearchArticleResponse.from(a), r.getId(), r.getQuery(), r.getCreatedAt());
        }).toList();
    }

    // Az orvos (a felület) a megnyitott oldal cikkeinek értékelését kéri; a már kész / folyamatban
    // lévő cikk kimarad. A kizárt cikket nem értékeljük (feleslegesen foglalná a GPU-t).
    @Transactional
    public ResearchResponse requestEvaluation(Long researchId, List<Long> articleIds) {
        Research research = reload(researchId);
        if (research.getStatus() != ResearchStatus.DONE) {
            throw new ResearchConflictException("A keresés még nem készült el");
        }
        List<ResearchArticle> articles = articleRepository.findByResearchIdAndIdIn(researchId, articleIds);
        if (articles.size() != Set.copyOf(articleIds).size()) {
            throw new ResearchNotFoundException("cikk ebben a kutatásban", articleIds.getFirst());
        }
        dispatchEvaluation(research, articles);
        return response(research);
    }

    // Python: keresés közbeni állapotüzenet
    @Transactional
    public void progress(Long id, String message) {
        if (message != null && !message.isBlank()) {
            reload(id).progress(message.strip().length() > 500 ? message.strip().substring(0, 500) : message.strip());
        }
    }

    // Python: a találatok. Az első oldal értékelése rögtön indul (a többi lapozáskor).
    @Transactional
    public ResearchResponse complete(Long id, ResearchResultRequest result) {
        Research research = reload(id);
        if (research.getStatus() != ResearchStatus.SEARCHING) {
            throw new ResearchConflictException("A(z) " + id + " keresés eredménye már megérkezett");
        }
        validate(result);
        if ("FAILED".equals(result.status())) {
            research.fail(truncate("Keresés: " + (result.error() == null ? "ismeretlen hiba" : result.error())));
            return response(research);
        }
        List<ResearchResultRequest.FoundArticle> found = result.articles() == null ? List.of() : result.articles();
        if (found.stream().map(ResearchResultRequest.FoundArticle::pmid).distinct().count() != found.size()
                || found.stream().map(ResearchResultRequest.FoundArticle::rank).distinct().count() != found.size()) {
            throw new IllegalArgumentException("A találati lista ismétlődő PMID-et vagy sorszámot tartalmaz");
        }
        List<ResearchArticle> saved = articleRepository.saveAll(found.stream()
                .map(a -> new ResearchArticle(id, a.rank(), a.pmid(), a.doi(), a.title().strip(), a.authors(),
                        a.journal(), a.pubYear(), a.abstractText(), a.sources(), a.publicationTypes(),
                        a.citationCount()))
                .toList());
        research.complete(result.totalFound() == null ? saved.size() : result.totalFound());
        articleRepository.flush();
        dispatchEvaluation(research, articleRepository.findByResearchIdAndExcludedFalseOrderByRankAsc(id,
                PageRequest.of(0, PAGE_SIZE)));
        return response(research);
    }

    @Transactional
    public void fail(Long id, String error) {
        repository.findById(id).ifPresent(r -> r.fail(truncate(error == null || error.isBlank()
                ? "Ismeretlen hiba" : error.strip())));
    }

    // Python: egy cikk értékelése. Az érvénytelen eredmény is FAILED-del zárja (a controller hívja)
    @Transactional
    public ResearchArticleResponse completeEvaluation(Long researchId, Long articleId,
            ArticleEvaluationResultRequest result) {
        ResearchArticle article = article(researchId, articleId);
        if (article.getEvalStatus() != ArticleEvalStatus.PENDING) {
            throw new ResearchConflictException("A(z) " + articleId + " cikk értékelése nem vár eredményre");
        }
        validate(result);
        if ("FAILED".equals(result.status())) {
            article.failEvaluation(truncate(result.error() == null ? "ismeretlen hiba" : result.error()));
            return ResearchArticleResponse.from(article);
        }
        if (result.summary() == null || result.summary().isBlank() || result.relevance() == null
                || result.relevanceReason() == null || result.relevanceReason().isBlank() || result.model() == null) {
            throw new IllegalArgumentException("DONE eredményhez kötelező a summary, a relevance, a relevanceReason "
                    + "és a model");
        }
        ArticleEvaluationResultRequest.Model m = result.model();
        article.completeEvaluation(result.summary().strip(), result.relevance(), result.relevanceReason().strip(),
                result.warnings() == null ? List.of() : result.warnings(), m.modelName(), m.seed(), m.promptHash());
        return ResearchArticleResponse.from(article);
    }

    @Transactional
    public void failEvaluation(Long researchId, Long articleId, String error) {
        articleRepository.findById(articleId).filter(a -> a.getResearchId().equals(researchId))
                .ifPresent(a -> a.failEvaluation(truncate(error == null || error.isBlank() ? "Ismeretlen hiba"
                        : error.strip())));
    }

    // A dispatcher hívja, ha a Python nem fogadta a kérést
    @Transactional
    public void failEvaluations(List<Long> articleIds, String error) {
        articleRepository.findAllById(articleIds).forEach(a -> a.failEvaluation(error));
    }

    private void dispatchEvaluation(Research research, List<ResearchArticle> articles) {
        OffsetDateTime staleBefore = OffsetDateTime.now().minusMinutes(STALE_PENDING_MINUTES);
        List<ResearchEvaluationJobRequest.Article> jobs = articles.stream()
                .filter(a -> !a.isExcluded())
                .filter(a -> a.markPending(staleBefore))
                .map(a -> new ResearchEvaluationJobRequest.Article(a.getId(),
                        client.callbackUrl("/api/research/" + research.getId() + "/articles/" + a.getId()
                                + "/evaluation"),
                        a.getTitle(), a.getAbstractText(), a.getJournal(), a.getPubYear(), a.getPublicationTypes()))
                .toList();
        if (!jobs.isEmpty()) {
            events.publishEvent(new ResearchEvents.EvaluationRequested(new ResearchEvaluationJobRequest(
                    research.getId(), research.getContextText(), research.getExtraPrompt(), jobs)));
        }
    }

    private <T> void validate(T result) {
        Set<ConstraintViolation<T>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                    .collect(Collectors.joining("; ")));
        }
    }

    private ResearchResponse response(Research r) {
        return ResearchResponse.from(r, counts(List.of(r.getId())).getOrDefault(r.getId(),
                ResearchResponse.Counts.EMPTY));
    }

    private Map<Long, ResearchResponse.Counts> counts(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return articleRepository.countByResearch(ids).stream().collect(Collectors.toMap(
                ResearchArticleRepository.Counts::getResearchId,
                c -> new ResearchResponse.Counts(c.getTotal(), c.getExcluded(), c.getBookmarked(), c.getEvaluated(),
                        c.getPending())));
    }

    private Research reload(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResearchNotFoundException("kutatás", id));
    }

    private ResearchArticle article(Long researchId, Long articleId) {
        return articleRepository.findById(articleId).filter(a -> a.getResearchId().equals(researchId))
                .orElseThrow(() -> new ResearchNotFoundException("cikk ebben a kutatásban", articleId));
    }

    private static String truncate(String message) {
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
