package bme.mediai.processing.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bme.mediai.processing.service.InternalTokenVerifier;
import bme.mediai.processing.service.RecordingProcessingService;
import bme.mediai.recording.api.RecordingResponse;
import bme.mediai.recording.api.RecordingResultRequest;
import bme.mediai.recording.exception.RecordingConflictException;
import bme.mediai.recording.exception.RecordingNotFoundException;

// Python hangszolgáltatás → Spring: a felvétel feldolgozásának eredménye, belső tokennel (401).
// Az út /api/recordings/{id}/… alatt van, így a hozzáférési napló a felvétel betegéhez köti.
@RestController
@RequestMapping("/api/recordings")
public class RecordingCallbackController {

    private final RecordingProcessingService service;
    private final InternalTokenVerifier tokenVerifier;

    public RecordingCallbackController(RecordingProcessingService service, InternalTokenVerifier tokenVerifier) {
        this.service = service;
        this.tokenVerifier = tokenVerifier;
    }

    // Nincs @Valid: a validáció a service-ben fut, hogy az érvénytelen eredmény is FAILED-del zárjon
    @PostMapping("/{id}/processing-result")
    public RecordingResponse receive(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody RecordingResultRequest request) {
        tokenVerifier.verify(token);
        try {
            return service.complete(id, request);
        } catch (RecordingNotFoundException | RecordingConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            service.fail(id, "A hangfeldolgozás eredménye nem rögzíthető: " + e.getMessage());
            throw e;
        }
    }
}
