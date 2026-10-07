package bme.mediai.document.service;

import java.nio.file.Path;

// Feldolgozandó PDF került a rendszerbe. A commit UTÁN dolgozza fel a listener
// (processing modul), így a Python sosem kap olyan dokumentum-id-t, ami még nincs a DB-ben.
public record DocumentUploadedEvent(Long documentId, Long patientId, Path filePath) {
}
