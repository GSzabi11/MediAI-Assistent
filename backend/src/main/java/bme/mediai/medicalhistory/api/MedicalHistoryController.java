package bme.mediai.medicalhistory.api;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.medicalhistory.exception.MedicalHistoryConflictException;
import bme.mediai.medicalhistory.exception.MedicalHistoryNotFoundException;
import bme.mediai.medicalhistory.service.MedicalHistoryDocx;
import bme.mediai.medicalhistory.service.MedicalHistoryService;
import bme.mediai.processing.service.InternalTokenVerifier;

@RestController
@RequestMapping("/api/medical-histories")
public class MedicalHistoryController {

    private final MedicalHistoryService service;
    private final InternalTokenVerifier tokenVerifier;

    public MedicalHistoryController(MedicalHistoryService service, InternalTokenVerifier tokenVerifier) {
        this.service = service;
        this.tokenVerifier = tokenVerifier;
    }

    // 202: a háttérben készül (Python-sor); az állapot a Location-ön követhető
    @PostMapping
    public ResponseEntity<MedicalHistoryResponse> request(@Valid @RequestBody MedicalHistoryCreateRequest request) {
        MedicalHistoryResponse created = service.request(request.patientId(), request.userId());
        return ResponseEntity.accepted().location(URI.create("/api/medical-histories/" + created.id())).body(created);
    }

    // a beteg kórtörténetei, a legfrissebb elöl (előzmény is)
    @GetMapping
    public List<MedicalHistoryResponse> findByPatient(@RequestParam Long patientId) {
        // a docproc leállásakor elveszett feladat ne maradjon örökre "Készül…" (ld. failLostJobs)
        service.failLostJobs(patientId);
        return service.findByPatient(patientId);
    }

    @GetMapping("/{id}")
    public MedicalHistoryResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    // Az orvos szerkesztése; az AI eredetije megmarad
    @PutMapping("/{id}/edited-html")
    public MedicalHistoryResponse edit(@PathVariable Long id, @Valid @RequestBody MedicalHistoryEditRequest request) {
        return service.edit(id, request.userId(), request.html());
    }

    // DOCX-letöltés — a "/file" végződés miatt a hozzáférési naplóban EXPORT
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        MedicalHistoryService.Export export = service.export(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MedicalHistoryDocx.CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(export.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(export.content());
    }

    // Python → Spring, belső tokennel: a feldolgozás aktuális lépése (a felület haladásjelzője)
    @PostMapping("/{id}/progress")
    public ResponseEntity<Void> progress(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody MedicalHistoryProgressRequest request) {
        tokenVerifier.verify(token);
        service.progress(id, request.message(), request.step(), request.total());
        return ResponseEntity.noContent().build();
    }

    // Python → Spring, belső tokennel. Nincs @Valid: a validáció a service-ben fut, hogy az
    // érvénytelen eredmény is FAILED-del zárja a kórtörténetet.
    @PostMapping("/{id}/result")
    public MedicalHistoryResponse result(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody MedicalHistoryResultRequest result) {
        tokenVerifier.verify(token);
        try {
            return service.complete(id, result);
        } catch (MedicalHistoryNotFoundException | MedicalHistoryConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            service.fail(id, "A kórtörténet eredménye nem rögzíthető: " + e.getMessage());
            throw e;
        }
    }
}
