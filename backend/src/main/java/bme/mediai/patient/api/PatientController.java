package bme.mediai.patient.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.patient.service.PatientService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService service;

    public PatientController(PatientService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PatientResponse> create(
            @Valid @RequestBody PatientCreateRequest request) {
        PatientResponse created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/patients/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public PatientResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping
    public List<PatientResponse> findAll() {
        return service.findAll();
    }

    @PostMapping("/search")
    public List<PatientResponse> search(@RequestBody PatientSearchRequest request) {
        return service.search(request.taj(), request.name(), request.motherName(), request.birthDate());
    }
}