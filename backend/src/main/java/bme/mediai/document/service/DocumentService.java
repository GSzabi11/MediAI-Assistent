package bme.mediai.document.service;

import bme.mediai.document.api.DocumentResponse;
import bme.mediai.document.exception.DocumentNotFoundException;
import bme.mediai.document.exception.DuplicateDocumentException;
import bme.mediai.document.exception.ExtractedTextAlreadySetException;
import bme.mediai.document.exception.ExtractedTextMissingException;
import bme.mediai.document.persistence.Document;
import bme.mediai.document.exception.DocumentProcessingException;
import bme.mediai.document.persistence.DocumentRepository;
import bme.mediai.document.persistence.DocumentStatus;
import bme.mediai.document.persistence.DocumentType;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.PatientRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

@Service
public class DocumentService {

    public record StoredFile(Path path, String mimeType) {
    }

    // Feldolgozás alatt álló PDF mappája, amíg a Python be nem sorolja
    private static final String PROCESSING_FOLDER = "PROCESSING";

    private final DocumentRepository repository;
    private final PatientRepository patientRepository;
    private final ApplicationEventPublisher events;
    private final Path storageDir;

    public DocumentService(DocumentRepository repository, PatientRepository patientRepository,
            ApplicationEventPublisher events, @Value("${mediai.storage.documents-dir}") Path storageDir) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.events = events;
        this.storageDir = storageDir.toAbsolutePath().normalize();
    }

    // A hash-t, a méretet és a MIME-típust a szerver számolja a tényleges bájtokból,
    // nem a kliens állítja.
    //
    // PDF: a típust és a klinikai dátumot az automatikus feldolgozás határozza meg, ezért a
    // kliens nem adhatja meg (400). A dokumentum PROCESSING állapotban jön létre, és a commit
    // után a Python AI-szolgáltatás kapja meg. Más fájl (kép, szöveg): a típus kötelező, a
    // dokumentum rögtön PROCESSED, automatikus feldolgozás nincs.
    @Transactional
    public DocumentResponse upload(Long patientId, DocumentType type, LocalDate clinicalDate, MultipartFile file) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("A feltöltött fájl üres");
        }

        Path finalPath = null;
        String sha256 = null;
        try {
            Path patientDir = Files.createDirectories(storageDir.resolve(patientId.toString()));
            Path temp = Files.createTempFile(patientDir, "upload-", ".tmp");
            try {
                sha256 = copyAndHash(file, temp);
                if (repository.existsByPatientIdAndSha256(patientId, sha256)) {
                    throw new DuplicateDocumentException(patientId, sha256);
                }
                String mimeType = MimeTypeDetector.detect(temp);
                if (mimeType == null) {
                    throw new IllegalArgumentException(
                            "Nem támogatott fájltípus (PDF, PNG, JPEG vagy UTF-8 szöveg lehet)");
                }
                boolean pdf = mimeType.equals(MimeTypeDetector.PDF_MIME);
                if (pdf && (type != null || clinicalDate != null)) {
                    throw new IllegalArgumentException("PDF-nél a típust és a klinikai dátumot az automatikus "
                            + "feldolgozás határozza meg, a feltöltés nem adhatja meg");
                }
                if (!pdf && type == null) {
                    throw new IllegalArgumentException("Nem PDF dokumentumhoz kötelező a típus (type)");
                }
                long size = Files.size(temp);

                String relativePath = relativePath(patientId, pdf ? PROCESSING_FOLDER : type.name(), sha256, mimeType);
                finalPath = storageDir.resolve(relativePath);
                Files.createDirectories(finalPath.getParent());
                Files.move(temp, finalPath, StandardCopyOption.REPLACE_EXISTING);

                Document document = new Document(patientId, relativePath, type,
                        pdf ? DocumentStatus.PROCESSING : DocumentStatus.PROCESSED,
                        mimeType, size, sha256, clinicalDate);
                Document saved = repository.saveAndFlush(document);
                if (pdf) {
                    events.publishEvent(new DocumentUploadedEvent(saved.getId(), patientId, finalPath));
                }
                return DocumentResponse.from(saved);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            deleteQuietly(finalPath);
            throw new UncheckedIOException("A fájl mentése nem sikerült", e);
        } catch (DataIntegrityViolationException e) {
            // Párhuzamos feltöltés ugyanazzal a hash-sel: a fájl ugyanazon az úton a másik
            // (sikeres) feltöltésé, ezért itt NEM töröljük.
            throw new DuplicateDocumentException(patientId, sha256);
        } catch (RuntimeException e) {
            // Ha a DB-mentés elbukik, ne maradjon árva fájl a lemezen
            deleteQuietly(finalPath);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public DocumentResponse findById(Long id) {
        return DocumentResponse.from(getDocument(id));
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> findByPatient(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
        return repository.findByPatientIdOrderByUploadedAtDesc(patientId)
                .stream()
                .map(DocumentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public StoredFile loadFile(Long id) {
        Document document = getDocument(id);
        return new StoredFile(storageDir.resolve(document.getStoragePath()), document.getMimeType());
    }

    @Transactional(readOnly = true)
    public String getExtractedText(Long id) {
        Document document = getDocument(id);
        if (document.getExtractedText() == null) {
            throw new ExtractedTextMissingException(id);
        }
        return document.getExtractedText();
    }

    @Transactional
    public DocumentResponse setExtractedText(Long id, String text) {
        // Feldolgozás alatt a szöveget a Python-callback állítja be; egy kézi beírás elvenné tőle
        if (getDocument(id).getStatus() == DocumentStatus.PROCESSING) {
            throw new DocumentProcessingException(id);
        }
        if (repository.setExtractedText(id, text) == 0) {
            throw new ExtractedTextAlreadySetException(id);
        }
        return DocumentResponse.from(getDocument(id));
    }

    // A feldolgozott dokumentum fájlját a típus szerinti mappába helyezi: másolat most, a régi
    // példány törlése csak sikeres commit után; visszagörgetéskor az új példány törlődik.
    // Így a DB storage_path-ja és a lemez nem kerül ellentmondásba. Tranzakción belül hívandó.
    public String relocateToTypeFolder(Document document, DocumentType type) {
        String newRelative = relativePath(document.getPatientId(), type.name(), document.getSha256(),
                document.getMimeType());
        if (newRelative.equals(document.getStoragePath())) {
            return newRelative;
        }
        Path oldPath = storageDir.resolve(document.getStoragePath());
        Path newPath = storageDir.resolve(newRelative);
        try {
            Files.createDirectories(newPath.getParent());
            Files.copy(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("A(z) " + document.getId() + " dokumentum áthelyezése nem sikerült", e);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                deleteQuietly(status == STATUS_COMMITTED ? oldPath : newPath);
            }
        });
        return newRelative;
    }

    // {patientId}/{típus vagy PROCESSING}/{sha256}.{ext} — betegnév nincs az útban (GDPR, névütközés)
    private static String relativePath(Long patientId, String folder, String sha256, String mimeType) {
        return patientId + "/" + folder + "/" + sha256 + MimeTypeDetector.extension(mimeType);
    }

    private Document getDocument(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    private static String copyAndHash(MultipartFile file, Path target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(file.getInputStream(), digest)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A hibát, ami idáig vezetett, fontosabb továbbadni
        }
    }
}
