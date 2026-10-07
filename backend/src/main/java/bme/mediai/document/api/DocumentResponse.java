package bme.mediai.document.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentStatus;
import bme.mediai.document.persistence.DocumentType;

// A kinyert szöveg nincs benne (nagy lehet): GET /api/documents/{id}/text
public record DocumentResponse(
        Long id,
        Long patientId,
        // PDF-nél PROCESSING alatt null: a feldolgozás sorolja be
        DocumentType type,
        DocumentStatus status,
        String mimeType,
        Long sizeBytes,
        String sha256,
        boolean hasExtractedText,
        LocalDate clinicalDate,
        OffsetDateTime uploadedAt,
        OffsetDateTime processedAt,
        String processingError,
        // Amit a feldolgozás tudatosan kihagyott (pl. cenzorált laborérték)
        List<String> processingWarnings,
        Extraction extraction
) {
    // HOGY: melyik modell nyerte ki az adatokat (kézi feltöltésnél null)
    public record Extraction(String model, Long seed, String promptHash, String extractorVersion) {
    }

    public static DocumentResponse from(Document document) {
        Extraction extraction = document.getExtractionModel() == null
                ? null
                : new Extraction(document.getExtractionModel(), document.getExtractionSeed(),
                        document.getExtractionPromptHash(), document.getExtractorVersion());
        return new DocumentResponse(
                document.getId(), document.getPatientId(), document.getType(), document.getStatus(),
                document.getMimeType(), document.getSizeBytes(), document.getSha256(),
                document.getExtractedText() != null, document.getClinicalDate(), document.getUploadedAt(),
                document.getProcessedAt(), document.getProcessingError(),
                document.getProcessingWarnings() == null ? List.of() : document.getProcessingWarnings(),
                extraction);
    }
}
