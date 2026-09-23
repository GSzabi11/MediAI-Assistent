package bme.mediai.voiceprofile.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import bme.mediai.voiceprofile.persistence.VoiceProfile;

public record VoiceProfileResponse(
        Long id,
        Long userId,
        String wavPath,
        String embeddingModel,
        BigDecimal threshold,
        boolean active,
        BigDecimal netSpeechSec,
        String microphone,
        OffsetDateTime recordedAt,
        OffsetDateTime createdAt
) {
    public static VoiceProfileResponse from(VoiceProfile profile) {
        return new VoiceProfileResponse(
                profile.getId(), profile.getUserId(), profile.getWavPath(), profile.getEmbeddingModel(),
                profile.getThreshold(), profile.isActive(), profile.getNetSpeechSec(),
                profile.getMicrophone(), profile.getRecordedAt(), profile.getCreatedAt());
    }
}
