package bme.mediai.voiceprofile.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import bme.mediai.voiceprofile.persistence.VoiceProfile;
import bme.mediai.voiceprofile.persistence.VoiceProfileStatus;

// A hanglenyomat (embedding) nem kerül a válaszba: biometrikus adat, a kliensnek nem kell
public record VoiceProfileResponse(
        Long id,
        Long userId,
        VoiceProfileStatus status,
        String error,
        boolean active,
        boolean usableForRecording,
        String embeddingModel,
        BigDecimal threshold,
        BigDecimal netSpeechSec,
        Integer sentencesUsed,
        List<String> warnings,
        String microphone,
        OffsetDateTime recordedAt,
        OffsetDateTime createdAt
) {
    public static VoiceProfileResponse from(VoiceProfile profile) {
        return new VoiceProfileResponse(
                profile.getId(), profile.getUserId(), profile.getStatus(), profile.getError(), profile.isActive(),
                profile.isUsableForRecording(), profile.getEmbeddingModel(), profile.getThreshold(),
                profile.getNetSpeechSec(), profile.getSentencesUsed(), profile.getWarnings(),
                profile.getMicrophone(), profile.getRecordedAt(), profile.getCreatedAt());
    }
}
