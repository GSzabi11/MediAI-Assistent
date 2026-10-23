package bme.mediai.research.api;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.processing.service.InternalTokenVerifier;
import bme.mediai.research.exception.ResearchConflictException;
import bme.mediai.research.exception.ResearchNotFoundException;
import bme.mediai.research.service.ResearchService;

@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private final ResearchService service;
    private final InternalTokenVerifier tokenVerifier;

    public ResearchController(ResearchService service, InternalTokenVerifier tokenVerifier) {
        this.service = service;
        this.tokenVerifier = tokenVerifier;
    }

    // 202: a keresés a háttérben fut (Python), az állapot (progress) a Location-ön követhető
    @PostMapping
    public ResponseEntity<ResearchResponse> create(@Valid @RequestBody ResearchCreateRequest request) {
        ResearchResponse created = service.create(request);
        return ResponseEntity.accepted().location(URI.create("/api/research/" + created.id())).body(created);
    }

    // a beteg korábbi kutatásai, a legfrissebb elöl
    @GetMapping
    public List<ResearchResponse> findByPatient(@RequestParam Long patientId) {
        return service.findByPatient(patientId);
    }

    // a beteg könyvjelzős cikkei, minden kutatásból
    @GetMapping("/bookmarks")
    public List<BookmarkResponse> bookmarks(@RequestParam Long patientId) {
        return service.bookmarks(patientId);
    }

    @GetMapping("/{id}")
    public ResearchResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    // lapozható találati lista (oldalanként ResearchService.PAGE_SIZE), rang szerint
    @GetMapping("/{id}/articles")
    public ArticlePageResponse articles(@PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "ACTIVE") ResearchService.ArticleFilter filter) {
        return service.articles(id, filter, page);
    }

    @PatchMapping("/{id}/articles/{articleId}")
    public ResearchArticleResponse updateArticle(@PathVariable Long id, @PathVariable Long articleId,
            @RequestBody ArticleUpdateRequest request) {
        return service.updateArticle(id, articleId, request.excluded(), request.bookmarked());
    }

    // a megnyitott oldal még nem értékelt cikkeinek értékelése (202, a háttérben)
    @PostMapping("/{id}/evaluations")
    public ResponseEntity<ResearchResponse> evaluate(@PathVariable Long id,
            @Valid @RequestBody EvaluationRequest request) {
        return ResponseEntity.accepted().body(service.requestEvaluation(id, request.articleIds()));
    }

    // --- Python → Spring, belső tokennel ---

    @PostMapping("/{id}/progress")
    public ResponseEntity<Void> progress(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody ResearchProgressRequest request) {
        tokenVerifier.verify(token);
        service.progress(id, request.message());
        return ResponseEntity.noContent().build();
    }

    // Nincs @Valid: a validáció a service-ben fut, hogy az érvénytelen eredmény is FAILED-del zárjon
    @PostMapping("/{id}/result")
    public ResearchResponse result(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody ResearchResultRequest result) {
        tokenVerifier.verify(token);
        try {
            return service.complete(id, result);
        } catch (ResearchNotFoundException | ResearchConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            service.fail(id, "A keresés eredménye nem rögzíthető: " + e.getMessage());
            throw e;
        }
    }

    @PostMapping("/{id}/articles/{articleId}/evaluation")
    public ResearchArticleResponse evaluation(@PathVariable Long id, @PathVariable Long articleId,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody ArticleEvaluationResultRequest result) {
        tokenVerifier.verify(token);
        try {
            return service.completeEvaluation(id, articleId, result);
        } catch (ResearchNotFoundException | ResearchConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            service.failEvaluation(id, articleId, "Az értékelés nem rögzíthető: " + e.getMessage());
            throw e;
        }
    }
}
