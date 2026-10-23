package bme.mediai.recordingfinding.service;

import bme.mediai.recordingfinding.api.ExtractionJobRequest;

public record RecordingExtractionRequestedEvent(ExtractionJobRequest job) {
}
