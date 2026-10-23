package bme.mediai.summary.api;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.processing.service.InternalTokenVerifier;
import bme.mediai.summary.exception.ClinicalSummaryNotFoundException;
import bme.mediai.summary.exception.SummaryConflictException;
import bme.mediai.summary.service.ClinicalSummaryService;

@RestController
@RequestMapping("/api/clinical-summaries")
public class ClinicalSummaryController {

    private final ClinicalSummaryService service;
    private final InternalTokenVerifier tokenVerifier;

    public ClinicalSummaryController(ClinicalSummaryService service, InternalTokenVerifier tokenVerifier) {
        this.service = service;
        this.tokenVerifier = tokenVerifier;
    }

    // 202: a háttérben készül (Python-sor); az állapot a Location-ön követhető
    @PostMapping
    public ResponseEntity<ClinicalSummaryResponse> request(@Valid @RequestBody ClinicalSummaryCreateRequest request) {
        ClinicalSummaryResponse created = service.request(request.patientId(), request.userId());
        return ResponseEntity.accepted().location(URI.create("/api/clinical-summaries/" + created.id())).body(created);
    }

    // a beteg összefoglalói, a legfrissebb elöl (előzmény is)
    @GetMapping
    public List<ClinicalSummaryResponse> findByPatient(@RequestParam Long patientId) {
        // a docproc leállásakor elveszett feladat ne maradjon örökre "Készül…" (ld. failLostJobs)
        service.failLostJobs(patientId);
        return service.findByPatient(patientId);
    }

    @GetMapping("/{id}")
    public ClinicalSummaryResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    // Python → Spring, belső tokennel. Nincs @Valid: a validáció a service-ben fut, hogy az
    // érvénytelen eredmény is FAILED-del zárja az összefoglalót.
    @PostMapping("/{id}/result")
    public ClinicalSummaryResponse result(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody SummaryResultRequest result) {
        tokenVerifier.verify(token);
        try {
            return service.complete(id, result);
        } catch (ClinicalSummaryNotFoundException | SummaryConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            service.fail(id, "Az összefoglaló eredménye nem rögzíthető: " + e.getMessage());
            throw e;
        }
    }
}
