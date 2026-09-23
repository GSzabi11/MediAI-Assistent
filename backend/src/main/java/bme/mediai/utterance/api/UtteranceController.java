package bme.mediai.utterance.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import bme.mediai.utterance.service.UtteranceService;

import java.util.List;

@RestController
@RequestMapping("/api/recordings/{recordingId}/utterances")
public class UtteranceController {

    private final UtteranceService service;

    public UtteranceController(UtteranceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<UtteranceResponse> createAll(
            @PathVariable Long recordingId, @Valid @RequestBody UtteranceBatchRequest request) {
        return service.createAll(recordingId, request.utterances());
    }

    @GetMapping
    public List<UtteranceResponse> findAll(@PathVariable Long recordingId) {
        return service.findByRecording(recordingId);
    }
}
