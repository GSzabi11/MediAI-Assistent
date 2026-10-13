package bme.mediai.recordingsummary.api;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.processing.service.InternalTokenVerifier;
import bme.mediai.recordingsummary.exception.RecordingSummaryConflictException;
import bme.mediai.recordingsummary.exception.RecordingSummaryNotFoundException;
import bme.mediai.recordingsummary.service.RecordingSummaryService;

@RestController
@RequestMapping("/api")
public class RecordingSummaryController {

    private final RecordingSummaryService service;
    private final InternalTokenVerifier tokenVerifier;

    public RecordingSummaryController(RecordingSummaryService service, InternalTokenVerifier tokenVerifier) {
        this.service = service;
        this.tokenVerifier = tokenVerifier;
    }

    // a felvétel összefoglalói, a legfrissebb elöl (előzmény is)
    @GetMapping("/recordings/{recordingId}/summaries")
    public List<RecordingSummaryResponse> findAll(@PathVariable Long recordingId) {
        return service.findByRecording(recordingId);
    }

    // 202: újragenerálás (pl. kulcsszó hozzáadása után); a háttérben készül
    @PostMapping("/recordings/{recordingId}/summaries")
    public ResponseEntity<RecordingSummaryResponse> request(@PathVariable Long recordingId,
            @Valid @RequestBody RecordingSummaryCreateRequest request) {
        RecordingSummaryResponse created = service.request(recordingId, request.userId());
        return ResponseEntity.accepted()
                .location(URI.create("/api/recordings/" + recordingId + "/summaries"))
                .body(created);
    }

    // Python → Spring, belső tokennel. Nincs @Valid: a validáció a service-ben fut, hogy az
    // érvénytelen eredmény is FAILED-del zárja az összefoglalót.
    @PostMapping("/recording-summaries/{id}/result")
    public RecordingSummaryResponse result(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody RecordingSummaryResultRequest result) {
        tokenVerifier.verify(token);
        try {
            return service.complete(id, result);
        } catch (RecordingSummaryNotFoundException | RecordingSummaryConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            service.fail(id, "Az összefoglaló eredménye nem rögzíthető: " + e.getMessage());
            throw e;
        }
    }
}
