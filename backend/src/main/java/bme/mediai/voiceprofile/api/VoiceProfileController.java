package bme.mediai.voiceprofile.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import bme.mediai.processing.service.InternalTokenVerifier;
import bme.mediai.voiceprofile.exception.VoiceProfileConflictException;
import bme.mediai.voiceprofile.exception.VoiceProfileNotFoundException;
import bme.mediai.voiceprofile.service.VoiceProfileService;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/voice-profiles")
public class VoiceProfileController {

    private final VoiceProfileService service;
    private final InternalTokenVerifier tokenVerifier;

    public VoiceProfileController(VoiceProfileService service, InternalTokenVerifier tokenVerifier) {
        this.service = service;
        this.tokenVerifier = tokenVerifier;
    }

    // 202: a felolvasott mondatokból a Python számolja a hanglenyomatot; az állapot a
    // Location-ön követhető. A userId a kérésből jön (még nincs bejelentkezés).
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VoiceProfileResponse> enroll(
            @RequestParam Long userId,
            @RequestPart("sentences") List<MultipartFile> sentences,
            @RequestPart(name = "noise", required = false) MultipartFile noise,
            @RequestParam(required = false) String microphone) {
        VoiceProfileResponse created = service.enroll(userId, sentences, noise, microphone);
        return ResponseEntity.accepted().location(URI.create("/api/voice-profiles/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public VoiceProfileResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping("/{userId}/active")
    public VoiceProfileResponse getActive(@PathVariable Long userId) {
        return service.findActive(userId);
    }

    @GetMapping("/{userId}/history")
    public List<VoiceProfileResponse> getHistory(@PathVariable Long userId) {
        return service.findHistory(userId);
    }

    // Python → Spring, belső tokennel. Nincs @Valid: a validáció a service-ben fut, hogy az
    // érvénytelen eredmény is FAILED-del zárja a profilt.
    @PostMapping("/{id}/enrollment-result")
    public VoiceProfileResponse enrollmentResult(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody VoiceEnrollmentResultRequest result) {
        tokenVerifier.verify(token);
        try {
            return service.completeEnrollment(id, result);
        } catch (VoiceProfileNotFoundException | VoiceProfileConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            service.failEnrollment(id, "A hangprofil eredménye nem rögzíthető: " + e.getMessage());
            throw e;
        }
    }
}
