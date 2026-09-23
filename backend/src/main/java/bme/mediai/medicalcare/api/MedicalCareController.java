package bme.mediai.medicalcare.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.medicalcare.service.MedicalCareService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/medical-cares")
public class MedicalCareController {

    private final MedicalCareService service;

    public MedicalCareController(MedicalCareService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<MedicalCareResponse> create(
            @Valid @RequestBody MedicalCareCreateRequest request) {
        MedicalCareResponse created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/medical-cares/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public MedicalCareResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping
    public List<MedicalCareResponse> findByPatient(@RequestParam Long patientId) {
        return service.findByPatient(patientId);
    }

    @PostMapping("/{id}/close")
    public MedicalCareResponse close(@PathVariable Long id) {
        return service.close(id);
    }
}
