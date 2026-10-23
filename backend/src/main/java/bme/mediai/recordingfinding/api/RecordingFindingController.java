package bme.mediai.recordingfinding.api;

import java.net.URI;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.processing.service.InternalTokenVerifier;
import bme.mediai.recordingfinding.exception.RecordingFindingConflictException;
import bme.mediai.recordingfinding.exception.RecordingFindingNotFoundException;
import bme.mediai.recordingfinding.service.RecordingFindingService;

// Az orvosi utak /api/recordings/{id}/… alatt vannak, így a hozzáférési napló a felvétel betegéhez köti
@RestController
@RequestMapping("/api")
public class RecordingFindingController {

    private final RecordingFindingService service;
    private final InternalTokenVerifier tokenVerifier;

    public RecordingFindingController(RecordingFindingService service, InternalTokenVerifier tokenVerifier) {
        this.service = service;
        this.tokenVerifier = tokenVerifier;
    }

    @GetMapping("/recordings/{recordingId}/findings")
    public FindingsResponse findAll(@PathVariable Long recordingId) {
        return service.findByRecording(recordingId);
    }

    // 202: újrakinyerés; a háttérben készül. Az új futás AI-elemei váltják a korábbiakat, az orvosé marad.
    @PostMapping("/recordings/{recordingId}/findings/extractions")
    public ResponseEntity<ExtractionResponse> extract(@PathVariable Long recordingId,
            @Valid @RequestBody FindingUserRequest request) {
        ExtractionResponse created = service.request(recordingId, request.userId());
        return ResponseEntity.accepted()
                .location(URI.create("/api/recordings/" + recordingId + "/findings"))
                .body(created);
    }

    @PostMapping("/recordings/{recordingId}/findings")
    @ResponseStatus(HttpStatus.CREATED)
    public FindingResponse add(@PathVariable Long recordingId, @Valid @RequestBody FindingCreateRequest request) {
        return service.add(recordingId, request);
    }

    // Az orvos szerkesztése (az eredeti megmarad)
    @PutMapping("/recordings/{recordingId}/findings/{id}")
    public FindingResponse edit(@PathVariable Long recordingId, @PathVariable Long id,
            @Valid @RequestBody FindingEditRequest request) {
        return service.edit(recordingId, id, request.userId(), request.text(), request.bnoCode());
    }

    // Logikai törlés (POST, mert a userId a törzsben jön); a törölt elemet adja vissza
    @PostMapping("/recordings/{recordingId}/findings/{id}/delete")
    public FindingResponse delete(@PathVariable Long recordingId, @PathVariable Long id,
            @Valid @RequestBody FindingUserRequest request) {
        return service.delete(recordingId, id, request.userId());
    }

    // Python → Spring, belső tokennel. Nincs @Valid: a validáció a service-ben fut, hogy az
    // érvénytelen eredmény is FAILED-del zárja a futást.
    @PostMapping("/recording-extractions/{id}/result")
    public ExtractionResponse result(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody ExtractionResultRequest result) {
        tokenVerifier.verify(token);
        try {
            return service.complete(id, result);
        } catch (RecordingFindingNotFoundException | RecordingFindingConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            service.fail(id, "A kinyerés eredménye nem rögzíthető: " + e.getMessage());
            throw e;
        }
    }
}
