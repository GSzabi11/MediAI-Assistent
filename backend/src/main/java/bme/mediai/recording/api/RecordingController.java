package bme.mediai.recording.api;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import bme.mediai.recording.persistence.RecordingMode;
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

    // A böngészőben készült felvétel (multipart, 202): a feldolgozás a Python hangszolgáltatásban
    // fut, az állapot a Location-ön követhető. A paraméterek a multipart törzsben jönnek, nem az URL-ben.
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RecordingResponse> upload(
            @RequestParam Long patientId,
            @RequestParam Long userId,
            @RequestParam(required = false) Long medicalCareId,
            @RequestParam RecordingMode mode,
            @RequestParam Integer durationSec,
            @RequestPart("audio") MultipartFile audio) {
        RecordingResponse created = service.upload(patientId, userId, medicalCareId, mode, durationSec, audio);
        return ResponseEntity.accepted().location(URI.create("/api/recordings/" + created.id())).body(created);
    }

    // Kézi nyilvántartás (pl. a Python CLI-prototípus kimenetének importjához): csak metaadat
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
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
