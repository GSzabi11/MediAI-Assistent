package bme.mediai.statement.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import bme.mediai.statement.service.StatementApprovalService;

import java.util.List;

@RestController
@RequestMapping("/api/statements/{statementId}/approvals")
public class StatementApprovalController {

    private final StatementApprovalService service;

    public StatementApprovalController(StatementApprovalService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalResponse create(
            @PathVariable Long statementId, @Valid @RequestBody ApprovalCreateRequest request) {
        return service.create(statementId, request);
    }

    @GetMapping
    public List<ApprovalResponse> history(@PathVariable Long statementId) {
        return service.findHistory(statementId);
    }
}
