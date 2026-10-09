package bme.mediai.chat.api;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import bme.mediai.audit.api.AccessLogInterceptor;
import bme.mediai.chat.exception.ChatConflictException;
import bme.mediai.chat.exception.ChatMessageNotFoundException;
import bme.mediai.chat.service.ChatService;
import bme.mediai.processing.service.InternalTokenVerifier;

// Nincs listázó végpont: a beszélgetés nem tárolódik, újratöltés után (és más felhasználónak) üres.
// Az üzenet nincs adatbázisban, ezért a hozzáférési napló a beteget kérés-attribútumból kapja.
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
    public ResponseEntity<ChatMessageResponse> ask(@Valid @RequestBody ChatMessageCreateRequest request,
            HttpServletRequest http) {
        http.setAttribute(AccessLogInterceptor.PATIENT_ATTRIBUTE, request.patientId());
        ChatMessageResponse created = service.ask(request.patientId(), request.userId(), request.question(),
                request.previousMessageIds());
        return ResponseEntity.accepted().location(URI.create("/api/chat-messages/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public ChatMessageResponse get(@PathVariable Long id, HttpServletRequest http) {
        logPatient(id, http);
        return service.findById(id);
    }

    // Python → Spring a feldolgozás közben, belső tokennel: vektoros keresés a beteg dokumentumaiban
    @PostMapping("/{id}/retrieve")
    public ChatRetrieveResponse retrieve(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody ChatRetrieveRequest request, HttpServletRequest http) {
        tokenVerifier.verify(token);
        logPatient(id, http);
        return service.retrieve(id, request);
    }

    // Python → Spring, belső tokennel. Nincs @Valid: a validáció a service-ben fut, hogy az
    // érvénytelen eredmény is FAILED-del zárja a kérdést.
    @PostMapping("/{id}/result")
    public ChatMessageResponse result(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody ChatResultRequest result, HttpServletRequest http) {
        tokenVerifier.verify(token);
        logPatient(id, http);
        try {
            return service.complete(id, result);
        } catch (ChatMessageNotFoundException | ChatConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            service.fail(id, "A válasz nem rögzíthető: " + e.getMessage());
            throw e;
        }
    }

    private void logPatient(Long id, HttpServletRequest http) {
        Long patientId = service.patientIdOf(id);
        if (patientId != null) {
            http.setAttribute(AccessLogInterceptor.PATIENT_ATTRIBUTE, patientId);
        }
    }
}
