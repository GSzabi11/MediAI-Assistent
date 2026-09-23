package bme.mediai.voiceprofile.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.voiceprofile.service.VoiceProfileService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/voice-profiles")
public class VoiceProfileController {

    private final VoiceProfileService service;

    public VoiceProfileController(VoiceProfileService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<VoiceProfileResponse> create(
            @Valid @RequestBody VoiceProfileCreateRequest request) {
        VoiceProfileResponse created = service.create(request);
        return ResponseEntity
                .created(URI.create("/api/voice-profiles/" + created.userId() + "/active"))
                .body(created);
    }

    @GetMapping("/{userId}/active")
    public VoiceProfileResponse getActive(@PathVariable Long userId) {
        return service.findActive(userId);
    }

    @GetMapping("/{userId}/history")
    public List<VoiceProfileResponse> getHistory(@PathVariable Long userId) {
        return service.findHistory(userId);
    }
}
