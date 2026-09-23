package bme.mediai.document.service;

import bme.mediai.document.api.DocumentResponse;
import bme.mediai.document.exception.DocumentNotFoundException;
import bme.mediai.document.exception.DuplicateDocumentException;
import bme.mediai.document.exception.ExtractedTextAlreadySetException;
import bme.mediai.document.exception.ExtractedTextMissingException;
import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentRepository;
import bme.mediai.document.persistence.DocumentType;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.PatientRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private final DocumentRepository repository;
    private final PatientRepository patientRepository;
    private final Path storageDir;

    public DocumentService(DocumentRepository repository, PatientRepository patientRepository,
            @Value("${mediai.storage.documents-dir}") Path storageDir) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.storageDir = storageDir.toAbsolutePath().normalize();
    }

    // A hash-t, a méretet és a MIME-típust a szerver számolja a tényleges bájtokból,
    // nem a kliens állítja.
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
                long size = Files.size(temp);

                String relativePath = patientId + "/" + sha256 + MimeTypeDetector.extension(mimeType);
                finalPath = storageDir.resolve(relativePath);
                Files.move(temp, finalPath, StandardCopyOption.REPLACE_EXISTING);

                Document document = new Document(patientId, relativePath, type, mimeType, size, sha256, clinicalDate);
                return DocumentResponse.from(repository.saveAndFlush(document));
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
        getDocument(id);
        if (repository.setExtractedText(id, text) == 0) {
            throw new ExtractedTextAlreadySetException(id);
        }
        return DocumentResponse.from(getDocument(id));
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
