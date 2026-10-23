package bme.mediai.drug.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bme.mediai.drug.service.DrugInteractionService;
import bme.mediai.drug.service.DrugReferenceRefresh;
import jakarta.validation.Valid;

// Gyógyszer-interakció (docs/drug-interaction.md). A beteghez kötött végpontok a /api/patients/{id} alatt
// vannak, hogy a hozzáférési napló a beteget is rögzítse (PatientResolver az útból).
@RestController
public class DrugInteractionController {

    private final DrugInteractionService service;
    private final DrugReferenceRefresh refresh;

    public DrugInteractionController(DrugInteractionService service, DrugReferenceRefresh refresh) {
        this.service = service;
        this.refresh = refresh;
    }

    @GetMapping("/api/drug-reference/status")
    public DrugReferenceStatusResponse status() {
        return service.status();
    }

    // Kézi frissítés (PUPHA + DDInter letöltése és betöltése) → 202; ha már fut, 409
    @PostMapping("/api/drug-reference/refresh")
    public ResponseEntity<DrugReferenceStatusResponse> startRefresh() {
        if (!refresh.tryStart()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(service.status());
        }
        refresh.runStarted();
        return ResponseEntity.accepted().body(service.status());
    }

    // Márkanév-javaslatok a beíráshoz (a gyógyszernév nem betegadat, mehet query stringben)
    @GetMapping("/api/drug-reference/suggestions")
    public List<String> suggestions(@RequestParam String q) {
        return service.suggestions(q);
    }

    @GetMapping("/api/patients/{patientId}/drug-interactions/coverage")
    public DrugCoverageResponse coverage(@PathVariable Long patientId) {
        return service.coverage(patientId);
    }

    @PostMapping("/api/patients/{patientId}/drug-interactions/check")
    public DrugCheckResponse check(@PathVariable Long patientId, @Valid @RequestBody DrugCheckRequest request) {
        return service.check(patientId, request.drugs());
    }
}
