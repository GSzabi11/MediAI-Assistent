package bme.mediai.recordingfinding.service;

// A felvétel automatikus csoport-kinyerése lezárult (DONE, FAILED, vagy el sem indult): erre indul az első
// összefoglaló, hogy a csoportokat már bemenetként kapja
public record RecordingFindingsSettledEvent(Long recordingId) {
}
