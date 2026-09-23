package bme.mediai.recording.exception;

import bme.mediai.recording.persistence.RecordingStatus;

public class InvalidStatusTransitionException extends RuntimeException {

    public InvalidStatusTransitionException(Long id, RecordingStatus from, RecordingStatus to) {
        super("A(z) " + id + " felvétel nem léptethető " + from + " állapotból " + to + " állapotba");
    }
}
