package bme.mediai.processing.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentRepository;
import bme.mediai.document.persistence.DocumentStatus;

// Félbemaradt dokumentum-feldolgozás folytatása. A Python sora csak memóriában él, és a Spring
// @Async átadása is elveszik leálláskor: a PROCESSING dokumentumon ilyenkor senki sem dolgozik,
// a callback sosem jön. Induláskor és utána percenként a Python sorában NEM szereplő PROCESSING
// PDF-eket újra átadjuk — a fájl a PROCESSING mappában maradt, a kinyert szöveg még NULL.
//
// Idempotens: a Python a már sorban álló dokumentumot nem veszi fel kétszer (alreadyQueued), a
// callback csak PROCESSING-ből ír. Átadás előtt újraolvassuk az állapotot, hogy a közben
// elkészült dokumentum ne fusson újra. Ha a Python nem érhető el, a dokumentum PROCESSING marad
// (nem FAILED, mint feltöltéskor): a docproc elindulása után a következő körben folytatódik.
@Component
public class DocumentProcessingResume {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentProcessingResume.class);

    private final DocumentRepository documentRepository;
    private final AiServiceClient client;
    private final Path storageDir;
    private boolean unreachableLogged;

    public DocumentProcessingResume(DocumentRepository documentRepository, AiServiceClient client,
            @Value("${mediai.storage.documents-dir}") Path storageDir) {
        this.documentRepository = documentRepository;
        this.client = client;
        this.storageDir = storageDir.toAbsolutePath().normalize();
    }

    // Szándékosan NEM tranzakcióban: a HTTP-hívások alatt ne tartsunk nyitva adatbázis-kapcsolatot
    @Scheduled(initialDelay = 15, fixedDelay = 60, timeUnit = TimeUnit.SECONDS)
    public void resumeLostJobs() {
        List<Long> processing = documentRepository.findProcessingPdfIds();
        if (processing.isEmpty()) {
            return;
        }
        // Régi Python-verzió (nincs "pending" a /health-ben): nem tudjuk, mi fut nála, ne küldjük újra
        Optional<Set<String>> pending = client.pendingJobs();
        if (pending.isEmpty()) {
            return;
        }
        int resumed = 0;
        for (Long id : processing) {
            if (pending.get().contains("document:" + id)) {
                continue;
            }
            Optional<Document> document = documentRepository.findById(id)
                    .filter(d -> d.getStatus() == DocumentStatus.PROCESSING);
            if (document.isEmpty()) {
                continue;
            }
            try {
                client.submit(id, storageDir.resolve(document.get().getStoragePath()).toString());
                resumed++;
            } catch (RestClientException e) {
                if (!unreachableLogged) {
                    LOG.warn("Az AI-szolgáltatás nem érhető el, {} dokumentum feldolgozása vár: {}",
                            processing.size(), e.getMessage());
                    unreachableLogged = true;
                }
                return;
            }
        }
        unreachableLogged = false;
        if (resumed > 0) {
            LOG.info("{} félbemaradt dokumentum-feldolgozás újra átadva az AI-szolgáltatásnak", resumed);
        }
    }
}
