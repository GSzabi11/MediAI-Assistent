package bme.mediai.chat.api;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.chat.exception.ChatConflictException;
import bme.mediai.chat.exception.ChatMessageNotFoundException;
import bme.mediai.chat.service.ChatService;
import bme.mediai.processing.service.InternalTokenVerifier;

@RestController
@RequestMapping("/api/chat-messages")
public class ChatMessageController {

    private final ChatService service;
    private final InternalTokenVerifier tokenVerifier;

    public ChatMessageController(ChatService service, InternalTokenVerifier tokenVerifier) {
        this.service = service;
        this.tokenVerifier = tokenVerifier;
    }

    // 202: a háttérben készül (Python-sor); az állapot a Location-ön követhető.
    // A kérdés a törzsben megy (érzékeny adat lehet), sosem query stringben.
    @PostMapping
    public ResponseEntity<ChatMessageResponse> ask(@Valid @RequestBody ChatMessageCreateRequest request) {
        ChatMessageResponse created = service.ask(request.patientId(), request.userId(), request.question(),
                request.previousMessageIds());
        return ResponseEntity.accepted().location(URI.create("/api/chat-messages/" + created.id())).body(created);
    }

    // a beteg legutóbbi (legfeljebb 50) kérdése, a legfrissebb elöl
    @GetMapping
    public List<ChatMessageResponse> findByPatient(@RequestParam Long patientId) {
        return service.findByPatient(patientId);
    }

    @GetMapping("/{id}")
    public ChatMessageResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    // Python → Spring a feldolgozás közben, belső tokennel: vektoros keresés a beteg dokumentumaiban
    @PostMapping("/{id}/retrieve")
    public ChatRetrieveResponse retrieve(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody ChatRetrieveRequest request) {
        tokenVerifier.verify(token);
        return service.retrieve(id, request);
    }

    // Python → Spring, belső tokennel. Nincs @Valid: a validáció a service-ben fut, hogy az
    // érvénytelen eredmény is FAILED-del zárja a kérdést.
    @PostMapping("/{id}/result")
    public ChatMessageResponse result(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody ChatResultRequest result) {
        tokenVerifier.verify(token);
        try {
            return service.complete(id, result);
        } catch (ChatMessageNotFoundException | ChatConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            service.fail(id, "A válasz nem rögzíthető: " + e.getMessage());
            throw e;
        }
    }
}
