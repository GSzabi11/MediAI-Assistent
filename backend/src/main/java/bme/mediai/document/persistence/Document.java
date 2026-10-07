package bme.mediai.document.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import bme.mediai.document.exception.ExtractedTextMissingException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long patientId;

    // Feldolgozáskor a Python-callback a típus szerinti mappába helyezi át (native UPDATE)
    @Column(updatable = false)
    private String storagePath;

    // PDF-nél feltöltéskor NULL, a feldolgozás sorolja be (native UPDATE)
    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private DocumentType type;

    // Csak a DocumentRepository állapotváltó lekérdezései írják, atomikusan
    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private DocumentStatus status;

    private String mimeType;

    private Long sizeBytes;

    private String sha256;

    // Csak a DocumentRepository.setExtractedText() írja, egyszer (NULL → szöveg)
    @Column(insertable = false, updatable = false)
    private String extractedText;

    // PDF-nél a feldolgozás olvassa ki a dokumentum szövegéből (native UPDATE)
    @Column(updatable = false)
    private LocalDate clinicalDate;

    @Column(insertable = false, updatable = false)
    private OffsetDateTime processedAt;

    @Column(insertable = false, updatable = false)
    private String processingError;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(insertable = false, updatable = false)
    private List<String> processingWarnings;

    @Column(insertable = false, updatable = false)
    private String extractionModel;

    @Column(insertable = false, updatable = false)
    private Long extractionSeed;

    @Column(insertable = false, updatable = false)
    private String extractionPromptHash;

    @Column(insertable = false, updatable = false)
    private String extractorVersion;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime uploadedAt;

    protected Document() {
    }

    public Document(Long patientId, String storagePath, DocumentType type, DocumentStatus status,
            String mimeType, Long sizeBytes, String sha256, LocalDate clinicalDate) {
        if (status == DocumentStatus.PROCESSED && type == null) {
            throw new IllegalArgumentException("Feldolgozott dokumentumnak kötelező a típusa");
        }
        if (status == DocumentStatus.FAILED) {
            throw new IllegalArgumentException("Dokumentum nem jöhet létre hibás állapotban");
        }
        this.patientId = patientId;
        this.storagePath = storagePath;
        this.type = type;
        this.status = status;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.clinicalDate = clinicalDate;
    }

    public boolean isImageLike() {
        return mimeType.startsWith("image/") || mimeType.equals("application/pdf");
    }

    // A kinyert szöveg [charStart, charEnd) része. Az offszetek Unicode code pointban
    // értendők (mint Pythonban), nem UTF-16 egységben. Az állítás-forrás és a chunk is
    // ezt használja, hogy a kettő sose számoljon eltérően.
    public String textSpan(int charStart, int charEnd) {
        if (extractedText == null) {
            throw new ExtractedTextMissingException(id);
        }
        int length = extractedText.codePointCount(0, extractedText.length());
        if (charStart < 0 || charEnd <= charStart || charEnd > length) {
            throw new IllegalArgumentException("Érvénytelen karaktertartomány [" + charStart + ", " + charEnd
                    + ") a(z) " + id + " dokumentum szövegén (hossz: " + length + ")");
        }
        return extractedText.substring(
                extractedText.offsetByCodePoints(0, charStart),
                extractedText.offsetByCodePoints(0, charEnd));
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public DocumentType getType() {
        return type;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }

    public String getProcessingError() {
        return processingError;
    }

    public List<String> getProcessingWarnings() {
        return processingWarnings;
    }

    public String getExtractionModel() {
        return extractionModel;
    }

    public Long getExtractionSeed() {
        return extractionSeed;
    }

    public String getExtractionPromptHash() {
        return extractionPromptHash;
    }

    public String getExtractorVersion() {
        return extractorVersion;
    }

    public String getMimeType() {
        return mimeType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public LocalDate getClinicalDate() {
        return clinicalDate;
    }

    public OffsetDateTime getUploadedAt() {
        return uploadedAt;
    }
}
