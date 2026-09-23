package bme.mediai.allergy.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.allergy.service.AllergyService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/allergies")
public class AllergyController {

    private final AllergyService service;

    public AllergyController(AllergyService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AllergyResponse> create(@Valid @RequestBody AllergyCreateRequest request) {
        AllergyResponse created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/allergies/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public AllergyResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping
    public List<AllergyResponse> findByPatient(@RequestParam Long patientId) {
        return service.findByPatient(patientId);
    }
}
