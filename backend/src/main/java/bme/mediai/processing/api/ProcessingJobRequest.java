package bme.mediai.processing.api;

// Spring → Python: POST {mediai.ai-service.url}/jobs (docs/document-processing.md).
// A Python ugyanazon a gépen olvassa a fájlt; a callbackUrl-re küldi az eredményt.
public record ProcessingJobRequest(
        Long documentId,
        String filePath,
        String callbackUrl
) {
}
