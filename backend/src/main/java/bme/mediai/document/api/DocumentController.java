package bme.mediai.document.api;

import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import bme.mediai.document.persistence.DocumentType;
import bme.mediai.document.service.DocumentService;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    // multipart/form-data: file + patientId + type (+ clinicalDate) — a mezők a törzsben jönnek
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam Long patientId,
            @RequestParam DocumentType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate clinicalDate) {
        DocumentResponse created = service.upload(patientId, type, clinicalDate, file);
        return ResponseEntity
                .created(URI.create("/api/documents/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public DocumentResponse get(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping
    public List<DocumentResponse> findByPatient(@RequestParam Long patientId) {
        return service.findByPatient(patientId);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<FileSystemResource> download(@PathVariable Long id) {
        DocumentService.StoredFile stored = service.loadFile(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.mimeType()))
                .body(new FileSystemResource(stored.path()));
    }

    @GetMapping(value = "/{id}/text", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String getText(@PathVariable Long id) {
        return service.getExtractedText(id);
    }

    @PutMapping("/{id}/text")
    public DocumentResponse setText(@PathVariable Long id, @Valid @RequestBody ExtractedTextRequest request) {
        return service.setExtractedText(id, request.text());
    }
}
