package bme.mediai.research.api;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.processing.service.InternalTokenVerifier;
import bme.mediai.research.exception.ResearchConflictException;
import bme.mediai.research.exception.ResearchNotFoundException;
import bme.mediai.research.service.ResearchContextService;

@RestController
@RequestMapping("/api/research-contexts")
public class ResearchContextController {

    private final ResearchContextService service;
    private final InternalTokenVerifier tokenVerifier;

    public ResearchContextController(ResearchContextService service, InternalTokenVerifier tokenVerifier) {
        this.service = service;
        this.tokenVerifier = tokenVerifier;
    }

    // 202: a háttérben készül (Python-sor); az állapot a Location-ön követhető
    @PostMapping
    public ResponseEntity<ResearchContextResponse> request(@Valid @RequestBody ResearchContextCreateRequest request) {
        ResearchContextResponse created = service.request(request.patientId(), request.userId());
        return ResponseEntity.accepted().location(URI.create("/api/research-contexts/" + created.id())).body(created);
    }

    // a beteg összefoglalói, a legfrissebb elöl
    @GetMapping
    public List<ResearchContextResponse> findByPatient(@RequestParam Long patientId) {
        return service.findByPatient(patientId);
    }

    @GetMapping("/{id}")
    public ResearchContextResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    @PutMapping("/{id}/edited-text")
    public ResearchContextResponse edit(@PathVariable Long id, @Valid @RequestBody ResearchContextEditRequest request) {
        return service.edit(id, request.userId(), request.text(), request.query());
    }

    // Python → Spring, belső tokennel. Nincs @Valid: a validáció a service-ben fut, hogy az
    // érvénytelen eredmény is FAILED-del zárja az összefoglalót.
    @PostMapping("/{id}/result")
    public ResearchContextResponse result(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody ResearchContextResultRequest result) {
        tokenVerifier.verify(token);
        try {
            return service.complete(id, result);
        } catch (ResearchNotFoundException | ResearchConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            service.fail(id, "Az összefoglaló eredménye nem rögzíthető: " + e.getMessage());
            throw e;
        }
    }
}
