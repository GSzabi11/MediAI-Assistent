package bme.mediai.recording.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.recording.service.RecordingService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/recordings")
public class RecordingController {

    private final RecordingService service;

    public RecordingController(RecordingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RecordingResponse> create(
            @Valid @RequestBody RecordingCreateRequest request) {
        RecordingResponse created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/recordings/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public RecordingResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping
    public List<RecordingResponse> findByPatient(@RequestParam Long patientId) {
        return service.findByPatient(patientId);
    }

    @PatchMapping("/{id}/status")
    public RecordingResponse changeStatus(
            @PathVariable Long id, @Valid @RequestBody RecordingStatusRequest request) {
        return service.changeStatus(id, request.status(), request.durationSec());
    }
}
