package bme.mediai.lab.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import bme.mediai.lab.service.LabResultService;

import java.util.List;

@RestController
@RequestMapping("/api/lab-results")
public class LabResultController {

    private final LabResultService service;

    public LabResultController(LabResultService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<LabResultResponse> createAll(@Valid @RequestBody LabBatchRequest request) {
        return service.createAll(request);
    }

    @GetMapping("/{id}")
    public LabResultResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    // name nélkül: minden eredmény, legújabb elöl; name-mel: az adott vizsgálat trendje időrendben
    @GetMapping
    public List<LabResultResponse> findByPatient(
            @RequestParam Long patientId, @RequestParam(required = false) String name) {
        return service.findByPatient(patientId, name);
    }
}
