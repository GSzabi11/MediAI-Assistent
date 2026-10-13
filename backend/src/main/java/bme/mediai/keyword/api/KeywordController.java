package bme.mediai.keyword.api;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import bme.mediai.keyword.service.KeywordService;

// Az út /api/recordings/{id}/… alatt van, így a hozzáférési napló a felvétel betegéhez köti
@RestController
@RequestMapping("/api/recordings/{recordingId}/keywords")
public class KeywordController {

    private final KeywordService service;

    public KeywordController(KeywordService service) {
        this.service = service;
    }

    @GetMapping
    public List<KeywordResponse> findAll(@PathVariable Long recordingId) {
        return service.findByRecording(recordingId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KeywordResponse add(@PathVariable Long recordingId, @Valid @RequestBody KeywordCreateRequest request) {
        return service.addByDoctor(recordingId, request.userId(), request.term());
    }

    @PostMapping("/{keywordId}/decision")
    public KeywordResponse decide(@PathVariable Long recordingId, @PathVariable Long keywordId,
            @Valid @RequestBody KeywordDecisionRequest request) {
        return service.decide(recordingId, keywordId, request.userId(), request.status());
    }
}
