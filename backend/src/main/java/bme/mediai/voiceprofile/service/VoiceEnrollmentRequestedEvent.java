package bme.mediai.voiceprofile.service;

import bme.mediai.voiceprofile.api.VoiceEnrollmentJobRequest;

public record VoiceEnrollmentRequestedEvent(VoiceEnrollmentJobRequest job) {
}
