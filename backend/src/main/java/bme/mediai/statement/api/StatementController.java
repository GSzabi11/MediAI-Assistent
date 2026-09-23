package bme.mediai.statement.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.statement.service.StatementService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/statements")
public class StatementController {

    private final StatementService service;

    public StatementController(StatementService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<StatementResponse> create(@Valid @RequestBody StatementCreateRequest request) {
        StatementResponse created = service.createFromApi(request);
        return ResponseEntity
                .created(URI.create("/api/statements/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public StatementResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping
    public List<StatementResponse> findByPatient(@RequestParam Long patientId) {
        return service.findByPatient(patientId);
    }
}
