package bme.mediai.voiceprofile.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Az embedding (vector(256)) oszlopot szándékosan nem képezzük le:
// egyelőre a Python hangmodul írja/olvassa, a Spring csak metaadatot kezel.
@Entity
@Table(name = "voice_profile")
public class VoiceProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private String wavPath;

    private String embeddingModel;

    private BigDecimal threshold;

    private boolean active;

    private BigDecimal netSpeechSec;

    private String microphone;

    private OffsetDateTime recordedAt;

    @Generated
    @Column(insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected VoiceProfile() {
    }

    public VoiceProfile(Long userId, String wavPath, String embeddingModel, BigDecimal threshold,
            BigDecimal netSpeechSec, String microphone, OffsetDateTime recordedAt) {
        this.userId = userId;
        this.wavPath = wavPath;
        this.embeddingModel = embeddingModel;
        this.threshold = threshold;
        this.netSpeechSec = netSpeechSec;
        this.microphone = microphone;
        this.recordedAt = recordedAt;
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
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

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public boolean isActive() {
        return active;
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
