package bme.mediai.recording.service;

// A felvétel átirata elkészült (DONE): erre indul a csoport-kinyerés, annak végén az első összefoglaló
public record RecordingTranscribedEvent(Long recordingId) {
}
