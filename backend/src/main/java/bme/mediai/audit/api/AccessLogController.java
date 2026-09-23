package bme.mediai.audit.api;

import org.springframework.web.bind.annotation.*;

import bme.mediai.audit.service.AccessLogService;

import java.util.List;

// Csak olvasás: bejegyzés kizárólag az AccessLogInterceptoron keresztül keletkezik.
@RestController
@RequestMapping("/api/access-logs")
public class AccessLogController {

    private final AccessLogService service;

    public AccessLogController(AccessLogService service) {
        this.service = service;
    }

    // Pontosan az egyik: ?patientId= (ki nézte a beteget) vagy ?userId= (mit nézett a felhasználó)
    @GetMapping
    public List<AccessLogResponse> find(
            @RequestParam(required = false) Long patientId, @RequestParam(required = false) Long userId) {
        if ((patientId == null) == (userId == null)) {
            throw new IllegalArgumentException("Pontosan az egyiket add meg: patientId vagy userId");
        }
        return patientId != null ? service.findByPatient(patientId) : service.findByUser(userId);
    }
}
