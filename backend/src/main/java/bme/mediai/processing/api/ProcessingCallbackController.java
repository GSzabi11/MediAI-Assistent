package bme.mediai.processing.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bme.mediai.document.api.DocumentResponse;
import bme.mediai.document.exception.DocumentNotFoundException;
import bme.mediai.document.exception.DocumentNotProcessingException;
import bme.mediai.processing.service.DocumentProcessingService;
import bme.mediai.processing.service.InternalTokenVerifier;

// Python → Spring: a feldolgozás eredménye. Csak a belső tokennel hívható (401).
// Az út /api/documents/{id}/… alatt van, így a hozzáférési napló a dokumentum betegéhez köti.
@RestController
@RequestMapping("/api/documents")
public class ProcessingCallbackController {

    private final DocumentProcessingService service;
    private final InternalTokenVerifier tokenVerifier;

    public ProcessingCallbackController(DocumentProcessingService service, InternalTokenVerifier tokenVerifier) {
        this.service = service;
        this.tokenVerifier = tokenVerifier;
    }

    // Nincs @Valid: a validáció a service-ben fut, hogy egy érvénytelen eredmény is FAILED-del
    // zárja a dokumentumot (ld. DocumentProcessingService.validate).
    @PostMapping("/{id}/processing-result")
    public DocumentResponse receive(@PathVariable Long id,
            @RequestHeader(name = InternalTokenVerifier.HEADER, required = false) String token,
            @RequestBody ProcessingResultRequest request) {
        tokenVerifier.verify(token);
        try {
            return service.complete(id, request);
        } catch (DocumentNotFoundException | DocumentNotProcessingException e) {
            // Nincs mit lezárni: ismeretlen dokumentum, vagy már megérkezett az eredmény
            throw e;
        } catch (RuntimeException e) {
            // A complete() tranzakciója visszagördült — semmi sem került be félig; a dokumentum
            // FAILED lesz, a Python 4xx-et kap az okkal
            service.fail(id, "Az AI-feldolgozás eredménye nem rögzíthető: " + e.getMessage());
            throw e;
        }
    }
}
