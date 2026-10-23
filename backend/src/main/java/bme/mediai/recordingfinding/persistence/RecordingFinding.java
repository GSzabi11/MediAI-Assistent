package bme.mediai.recordingfinding.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Egy csoportosított elem az átiratból (V34). Az eredeti (text, bnoCode, turnIds) nem változik: az orvos
// szerkesztése az edited_* mezőkbe kerül, a törlés logikai — a modell javaslata visszakereshető marad.
@Entity
@Table(name = "recording_finding")
public class RecordingFinding {

    public static final int MAX_TEXT_LENGTH = 500;
    // betű + 2 számjegy, opcionálisan pont és 1–4 jel (I10, R07.4, K2190) — ugyanaz, mint a DB CHECK
    private static final Pattern BNO = Pattern.compile("^[A-Z][0-9]{2}(\\.?[0-9A-Z]{1,4})?$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long recordingId;

    private Long extractionId;

    @Enumerated(EnumType.STRING)
    private FindingCategory category;

    @Enumerated(EnumType.STRING)
    private FindingOrigin origin;

    private String text;

    private String bnoCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private List<Integer> turnIds;

    private String flag;

    private Long createdBy;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    private String editedText;

    private String editedBnoCode;

    private Long editedBy;

    private OffsetDateTime editedAt;

    private Long deletedBy;

    private OffsetDateTime deletedAt;

    protected RecordingFinding() {
    }

    private RecordingFinding(Long recordingId, Long extractionId, FindingCategory category, FindingOrigin origin,
            String text, String bnoCode, List<Integer> turnIds, String flag, Long createdBy) {
        this.recordingId = recordingId;
        this.extractionId = extractionId;
        this.category = category;
        this.origin = origin;
        this.text = cleanText(text);
        this.bnoCode = cleanBno(category, bnoCode);
        this.turnIds = List.copyOf(turnIds);
        this.flag = flag;
        this.createdBy = createdBy;
    }

    public static RecordingFinding fromModel(Long recordingId, Long extractionId, FindingCategory category,
            String text, String bnoCode, List<Integer> turnIds, String flag) {
        if (turnIds.isEmpty()) {
            throw new IllegalArgumentException("Az AI-elemnek forrás-megszólalásra kell hivatkoznia: " + text);
        }
        return new RecordingFinding(recordingId, extractionId, category, FindingOrigin.MODEL, text, bnoCode, turnIds,
                flag, null);
    }

    public static RecordingFinding addedByDoctor(Long recordingId, FindingCategory category, String text,
            String bnoCode, List<Integer> turnIds, Long userId) {
        return new RecordingFinding(recordingId, null, category, FindingOrigin.DOCTOR, text, bnoCode, turnIds, null,
                userId);
    }

    /** Az orvos szerkesztése; újraszerkesztés felülírja az előzőt, az eredeti megmarad. */
    public void edit(String text, String bnoCode, Long userId) {
        if (deletedAt != null) {
            throw new IllegalStateException("Törölt elem nem szerkeszthető");
        }
        String t = cleanText(text);
        String code = cleanBno(category, bnoCode);
        this.editedText = t;
        this.editedBnoCode = code;
        this.editedBy = userId;
        this.editedAt = OffsetDateTime.now();
    }

    public void delete(Long userId) {
        if (deletedAt != null) {
            throw new IllegalStateException("Az elem már törölve van");
        }
        this.deletedBy = userId;
        this.deletedAt = OffsetDateTime.now();
    }

    private static String cleanText(String raw) {
        String t = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (t.isEmpty() || t.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Az elem szövege 1–" + MAX_TEXT_LENGTH + " karakter lehet");
        }
        return t;
    }

    /** null / üres → nincs kód; különben nagybetűs, szóköz nélküli, formátum-ellenőrzött. */
    private static String cleanBno(FindingCategory category, String raw) {
        String code = raw == null ? "" : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            return null;
        }
        if (!category.allowsBnoCode()) {
            throw new IllegalArgumentException("BNO-kód csak panaszhoz és diagnózishoz adható");
        }
        if (!BNO.matcher(code).matches()) {
            throw new IllegalArgumentException("Érvénytelen BNO-kód: " + raw.strip() + " (pl. R07.4, I10)");
        }
        return code;
    }

    public boolean isEdited() {
        return editedAt != null;
    }

    public String getEffectiveText() {
        return isEdited() ? editedText : text;
    }

    public String getEffectiveBnoCode() {
        return isEdited() ? editedBnoCode : bnoCode;
    }

    public Long getId() {
        return id;
    }

    public Long getRecordingId() {
        return recordingId;
    }

    public Long getExtractionId() {
        return extractionId;
    }

    public FindingCategory getCategory() {
        return category;
    }

    public FindingOrigin getOrigin() {
        return origin;
    }

    public String getText() {
        return text;
    }

    public String getBnoCode() {
        return bnoCode;
    }

    public List<Integer> getTurnIds() {
        return turnIds == null ? List.of() : turnIds;
    }

    public String getFlag() {
        return flag;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public Long getEditedBy() {
        return editedBy;
    }

    public OffsetDateTime getEditedAt() {
        return editedAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }
}
