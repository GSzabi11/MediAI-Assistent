package bme.mediai.document.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentType;

// A kinyert szöveg nincs benne (nagy lehet): GET /api/documents/{id}/text
public record DocumentResponse(
        Long id,
        Long patientId,
        DocumentType type,
        String mimeType,
        Long sizeBytes,
        String sha256,
        boolean hasExtractedText,
        LocalDate clinicalDate,
        OffsetDateTime uploadedAt
) {
    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(), document.getPatientId(), document.getType(), document.getMimeType(),
                document.getSizeBytes(), document.getSha256(), document.getExtractedText() != null,
                document.getClinicalDate(), document.getUploadedAt());
    }
}
