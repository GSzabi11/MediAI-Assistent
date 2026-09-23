package bme.mediai.utterance.exception;

import bme.mediai.recording.persistence.RecordingStatus;

public class RecordingNotProcessingException extends RuntimeException {

    public RecordingNotProcessingException(Long recordingId, RecordingStatus status) {
        super("Megszólalás csak PROCESSING állapotú felvételhez adható; a(z) "
                + recordingId + " felvétel állapota: " + status);
    }
}
