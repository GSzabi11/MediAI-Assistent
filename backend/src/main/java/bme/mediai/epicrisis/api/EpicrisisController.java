package bme.mediai.epicrisis.api;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.epicrisis.exception.EpicrisisConflictException;
import bme.mediai.epicrisis.exception.EpicrisisNotFoundException;
import bme.mediai.epicrisis.service.EpicrisisService;
import bme.mediai.medicalhistory.api.MedicalHistoryProgressRequest;
import bme.mediai.medicalhistory.service.MedicalHistoryDocx;
import bme.mediai.processing.service.InternalTokenVerifier;

@RestController
@RequestMapping("/api/epicrises")
public class EpicrisisController {

    private final EpicrisisService service;
    private final InternalTokenVerifier tokenVerifier;

    public EpicrisisController(EpicrisisService service, InternalTokenVerifier tokenVerifier) {
        this.service = service;
        this.tokenVerifier = tokenVerifier;
    }

    // 202: a háttérben készül (Python-sor); az állapot a Location-ön követhető
    @PostMapping
    public ResponseEntity<EpicrisisResponse> request(@Valid @RequestBody EpicrisisCreateRequest request) {
        EpicrisisResponse created = service.request(request.medicalCareId(), request.userId());
        return ResponseEntity.accepted().location(URI.create("/api/epicrises/" + created.id())).body(created);
    }

    // a beteg epikrízisei (minden ellátásé), a legfrissebb elöl
    @GetMapping
    public List<EpicrisisResponse> findByPatient(@RequestParam Long patientId) {
        // a docproc leállásakor elveszett feladat ne maradjon örökre "Készül…"
        service.failLostJobs(patientId);
        return service.findByPatient(patientId);
    }

    @GetMapping("/{id}")
    public EpicrisisResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    // Az orvos szerkesztése; az AI eredetije megmarad
    @PutMapping("/{id}/edited-html")
    public EpicrisisResponse edit(@PathVariable Long id, @Valid @RequestBody EpicrisisEditRequest request) {
        return service.edit(id, request.userId(), request.html());
    }

    // DOCX-letöltés — a "/file" végződés miatt a hozzáférési naplóban EXPORT
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        EpicrisisService.Export export = service.export(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MedicalHistoryDocx.CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(export.content());
    }

    // Python → Spring, belső tokennel: a feldolgozás aktuális lépése
    @PostMapping("/{id}/progress")
    public ResponseEntity<Void> progress(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody MedicalHistoryProgressRequest request) {
        tokenVerifier.verify(token);
        service.progress(id, request.message(), request.step(), request.total());
        return ResponseEntity.noContent().build();
    }

    // Python → Spring, belső tokennel. Nincs @Valid: a validáció a service-ben fut, hogy az
    // érvénytelen eredmény is FAILED-del zárja az epikrízist.
    @PostMapping("/{id}/result")
    public EpicrisisResponse result(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody EpicrisisResultRequest result) {
        tokenVerifier.verify(token);
        try {
            return service.complete(id, result);
        } catch (EpicrisisNotFoundException | EpicrisisConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            service.fail(id, "Az epikrízis eredménye nem rögzíthető: " + e.getMessage());
            throw e;
        }
    }
}
