package bme.mediai.medication.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.medication.service.MedicationService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/medications")
public class MedicationController {

    private final MedicationService service;

    public MedicationController(MedicationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<MedicationResponse> create(@Valid @RequestBody MedicationCreateRequest request) {
        MedicationResponse created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/medications/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public MedicationResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    // activeOnly=true: csak a jelenleg szedett gyógyszerek
    @GetMapping
    public List<MedicationResponse> findByPatient(
            @RequestParam Long patientId, @RequestParam(defaultValue = "false") boolean activeOnly) {
        return service.findByPatient(patientId, activeOnly);
    }

    @PostMapping("/{id}/discontinue")
    public MedicationResponse discontinue(@PathVariable Long id) {
        return service.discontinue(id);
    }
}
