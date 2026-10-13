package bme.mediai.utterance.api;

import bme.mediai.utterance.persistence.Speaker;
import bme.mediai.utterance.persistence.Utterance;

public record UtteranceResponse(
        Long id,
        Long recordingId,
        Integer turnId,
        Speaker speaker,
        Float similarity,
        Integer startMs,
        Integer endMs,
        String text,
        Float asrConfidence,
        String asrText,
        Speaker asrSpeaker,
        String correctedBy,
        String correctionReason,
        String reviewNote
) {
    public static UtteranceResponse from(Utterance utterance) {
        return new UtteranceResponse(
                utterance.getId(), utterance.getRecordingId(), utterance.getTurnId(),
                utterance.getSpeaker(), utterance.getSimilarity(), utterance.getStartMs(),
                utterance.getEndMs(), utterance.getText(), utterance.getAsrConfidence(),
                utterance.getAsrText(), utterance.getAsrSpeaker(), utterance.getCorrectedBy(),
                utterance.getCorrectionReason(), utterance.getReviewNote());
    }
}
