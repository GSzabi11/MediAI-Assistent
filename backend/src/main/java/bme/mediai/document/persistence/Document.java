package bme.mediai.document.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.hibernate.annotations.Generated;

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

    private String storagePath;

    @Enumerated(EnumType.STRING)
    private DocumentType type;

    private String mimeType;

    private Long sizeBytes;

    private String sha256;

    // Csak a DocumentRepository.setExtractedText() írja, egyszer (NULL → szöveg)
    @Column(insertable = false, updatable = false)
    private String extractedText;

    private LocalDate clinicalDate;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime uploadedAt;

    protected Document() {
    }

    public Document(Long patientId, String storagePath, DocumentType type, String mimeType,
            Long sizeBytes, String sha256, LocalDate clinicalDate) {
        this.patientId = patientId;
        this.storagePath = storagePath;
        this.type = type;
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
