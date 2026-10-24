package bme.mediai.processing.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import bme.mediai.recording.persistence.Recording;
import bme.mediai.recording.persistence.RecordingMode;
import bme.mediai.recording.persistence.RecordingRepository;
import bme.mediai.recording.persistence.RecordingStatus;
import bme.mediai.recordingfinding.persistence.RecordingExtraction;
import bme.mediai.recordingfinding.persistence.RecordingExtractionRepository;
import bme.mediai.recordingfinding.service.RecordingFindingService;
import bme.mediai.recordingsummary.persistence.RecordingSummary;
import bme.mediai.recordingsummary.persistence.RecordingSummaryRepository;
import bme.mediai.recordingsummary.service.RecordingSummaryService;
import bme.mediai.voiceprofile.persistence.VoiceProfile;
import bme.mediai.voiceprofile.persistence.VoiceProfileRepository;
import bme.mediai.voiceprofile.service.VoiceProfileService;

class VoiceLostJobsTest {

    private final VoiceServiceClient client = mock(VoiceServiceClient.class);
    private final RecordingRepository recordings = mock(RecordingRepository.class);
    private final VoiceProfileRepository profiles = mock(VoiceProfileRepository.class);
    private final RecordingSummaryRepository summaries = mock(RecordingSummaryRepository.class);
    private final RecordingExtractionRepository extractions = mock(RecordingExtractionRepository.class);
    private final RecordingProcessingService recordingProcessing = mock(RecordingProcessingService.class);
    private final VoiceProfileService profileService = mock(VoiceProfileService.class);
    private final RecordingSummaryService summaryService = mock(RecordingSummaryService.class);
    private final RecordingFindingService findingService = mock(RecordingFindingService.class);
    private final VoiceLostJobs lostJobs = new VoiceLostJobs(client, recordings, profiles, summaries, extractions,
            recordingProcessing, profileService, summaryService, findingService, Path.of("data", "documents"));

    @Test
    void resubmitsLostRecordingAndFailsOtherLostJobs() {
        Recording lost = recording(1L, "5/hangfelvetelek/a.webm", 10L);
        Recording running = recording(2L, "5/hangfelvetelek/b.webm", 10L);
        Recording manualImport = recording(3L, "C:/cli/out.wav", 10L);
        when(recordings.findByStatus(RecordingStatus.PROCESSING)).thenReturn(List.of(lost, running, manualImport));
        when(recordings.findById(1L)).thenReturn(Optional.of(lost));
        VoiceProfile profile = mock(VoiceProfile.class);
        when(profile.getEmbedding()).thenReturn(new float[] {0.5f, 0.25f});
        when(profiles.findById(10L)).thenReturn(Optional.of(profile));
        RecordingSummary summary = mock(RecordingSummary.class);
        when(summary.getId()).thenReturn(7L);
        when(summaries.findByStatusAndRequestedAtBefore(any(), any())).thenReturn(List.of(summary));
        RecordingExtraction extraction = mock(RecordingExtraction.class);
        when(extraction.getId()).thenReturn(8L);
        when(extractions.findByStatusAndRequestedAtBefore(any(), any())).thenReturn(List.of(extraction));
        when(client.pendingJobs()).thenReturn(Optional.of(Set.of("recording:2", "findings:8")));
        when(client.callbackUrl(anyString())).thenAnswer(i -> "http://spring" + i.getArgument(0));

        lostJobs.handleLostJobs();

        verify(client).submitRecording(argThat(job -> job.recordingId().equals(1L)
                && job.doctorEmbedding().equals(List.of(0.5f, 0.25f)) && job.mode().equals("SBO")));
        verify(client, never()).submitRecording(argThat(job -> !job.recordingId().equals(1L)));
        verify(summaryService).fail(eq(7L), anyString());
        verify(findingService, never()).fail(anyLong(), anyString());
        verify(recordingProcessing, never()).fail(anyLong(), anyString());
    }

    @Test
    void failsRecordingWhoseProfileIsGone() {
        Recording lost = recording(1L, "5/hangfelvetelek/a.webm", null);
        when(recordings.findByStatus(RecordingStatus.PROCESSING)).thenReturn(List.of(lost));
        when(recordings.findById(1L)).thenReturn(Optional.of(lost));
        when(client.pendingJobs()).thenReturn(Optional.of(Set.of()));

        lostJobs.handleLostJobs();

        verify(recordingProcessing).fail(eq(1L), anyString());
        verify(client, never()).submitRecording(any());
    }

    private static Recording recording(Long id, String audioPath, Long profileId) {
        Recording r = mock(Recording.class);
        when(r.getId()).thenReturn(id);
        when(r.getStatus()).thenReturn(RecordingStatus.PROCESSING);
        when(r.getAudioPath()).thenReturn(audioPath);
        when(r.getVoiceProfileId()).thenReturn(profileId);
        when(r.getMode()).thenReturn(RecordingMode.SBO);
        return r;
    }
}
