package bme.mediai.recording.service;

import bme.mediai.recording.api.RecordingJobRequest;

public record RecordingUploadedEvent(RecordingJobRequest job) {
}
