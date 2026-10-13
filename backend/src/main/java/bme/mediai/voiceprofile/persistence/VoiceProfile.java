package bme.mediai.voiceprofile.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.hibernate.annotations.Array;
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

// V26 óta a profil a böngészőből készül: PROCESSING-ként jön létre (nem aktív), a hanglenyomatot
// (embedding) a Python hangszolgáltatás számolja, és a callback teszi READY-vé és aktívvá.
@Entity
@Table(name = "voice_profile")
public class VoiceProfile {

    // pyannote/wespeaker-voxceleb-resnet34-LM kimenete (V4: vector(256))
    public static final int EMBEDDING_DIMENSION = 256;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private String wavPath;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIMENSION)
    private float[] embedding;

    private String embeddingModel;

    private BigDecimal threshold;

    private boolean active;

    @Enumerated(EnumType.STRING)
    private VoiceProfileStatus status;

    private String error;

    private Integer sentencesUsed;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> warnings;

    private BigDecimal netSpeechSec;

    private String microphone;

    private OffsetDateTime recordedAt;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected VoiceProfile() {
    }

    // Felvétel a böngészőből: a hangminta már a lemezen van, a hanglenyomat még nincs kiszámolva
    public VoiceProfile(Long userId, String wavPath, String microphone, OffsetDateTime recordedAt) {
        this.userId = userId;
        this.wavPath = wavPath;
        this.microphone = microphone;
        this.recordedAt = recordedAt;
        this.status = VoiceProfileStatus.PROCESSING;
        this.active = false;
        this.warnings = List.of();
    }

    // A service a régi aktív profilt előbb inaktiválja és flush-ol (részleges egyedi index)
    public void complete(float[] embedding, String embeddingModel, BigDecimal netSpeechSec, int sentencesUsed,
            List<String> warnings) {
        if (status != VoiceProfileStatus.PROCESSING) {
            throw new IllegalStateException("A(z) " + id + " hangprofil már nem feldolgozás alatt áll");
        }
        if (embedding == null || embedding.length != EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException("A hanglenyomat " + EMBEDDING_DIMENSION + " dimenziós kell legyen");
        }
        this.embedding = embedding.clone();
        this.embeddingModel = embeddingModel;
        this.netSpeechSec = netSpeechSec;
        this.sentencesUsed = sentencesUsed;
        this.warnings = List.copyOf(warnings);
        this.status = VoiceProfileStatus.READY;
        this.active = true;
    }

    public void fail(String error) {
        if (status != VoiceProfileStatus.PROCESSING) {
            return;
        }
        this.error = error;
        this.status = VoiceProfileStatus.FAILED;
    }

    public void deactivate() {
        this.active = false;
    }

    // Felvételhez a hanglenyomat kell: a V4-es, kézzel rögzített profiloknál nincs
    public boolean isUsableForRecording() {
        return active && status == VoiceProfileStatus.READY && embedding != null;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getWavPath() {
        return wavPath;
    }

    public float[] getEmbedding() {
        return embedding == null ? null : embedding.clone();
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public boolean isActive() {
        return active;
    }

    public VoiceProfileStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Integer getSentencesUsed() {
        return sentencesUsed;
    }

    public List<String> getWarnings() {
        return warnings == null ? List.of() : warnings;
    }

    public BigDecimal getNetSpeechSec() {
        return netSpeechSec;
    }

    public String getMicrophone() {
        return microphone;
    }

    public OffsetDateTime getRecordedAt() {
        return recordedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
