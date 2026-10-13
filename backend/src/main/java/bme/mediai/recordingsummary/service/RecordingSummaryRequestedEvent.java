package bme.mediai.recordingsummary.service;

import bme.mediai.recordingsummary.api.RecordingSummaryJobRequest;

public record RecordingSummaryRequestedEvent(RecordingSummaryJobRequest job) {
}
