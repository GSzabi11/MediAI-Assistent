package bme.mediai.processing.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentRepository;
import bme.mediai.document.persistence.DocumentStatus;

class DocumentProcessingResumeTest {

    private final DocumentRepository repository = mock(DocumentRepository.class);
    private final AiServiceClient client = mock(AiServiceClient.class);
    private final Path storageDir = Path.of("data", "documents").toAbsolutePath().normalize();
    private final DocumentProcessingResume resume = new DocumentProcessingResume(repository, client, storageDir);

    @Test
    void resubmitsOnlyLostProcessingDocuments() {
        when(repository.findProcessingPdfIds()).thenReturn(List.of(1L, 2L, 3L));
        when(client.pendingJobs()).thenReturn(Optional.of(Set.of("document:1")));
        Document done = document(DocumentStatus.PROCESSED, "5/LAB/b.pdf");
        Document lost = document(DocumentStatus.PROCESSING, "5/PROCESSING/c.pdf");
        when(repository.findById(2L)).thenReturn(Optional.of(done));
        when(repository.findById(3L)).thenReturn(Optional.of(lost));

        resume.resumeLostJobs();

        verify(client, never()).submit(eq(1L), anyString());
        verify(client, never()).submit(eq(2L), anyString());
        verify(client).submit(3L, storageDir.resolve("5/PROCESSING/c.pdf").toString());
    }

    @Test
    void doesNothingWhenPendingListUnknown() {
        when(repository.findProcessingPdfIds()).thenReturn(List.of(1L));
        when(client.pendingJobs()).thenReturn(Optional.empty());

        resume.resumeLostJobs();

        verify(client, never()).submit(anyLong(), anyString());
    }

    private static Document document(DocumentStatus status, String storagePath) {
        Document d = mock(Document.class);
        when(d.getStatus()).thenReturn(status);
        when(d.getStoragePath()).thenReturn(storagePath);
        return d;
    }
}
