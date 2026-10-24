package bme.mediai.processing.service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import bme.mediai.common.storage.AudioStorage;
import bme.mediai.recording.api.RecordingJobRequest;
import bme.mediai.recording.persistence.Recording;
import bme.mediai.recording.persistence.RecordingRepository;
import bme.mediai.recording.persistence.RecordingStatus;
import bme.mediai.recording.service.RecordingService;
import bme.mediai.recordingfinding.persistence.RecordingExtraction;
import bme.mediai.recordingfinding.persistence.RecordingExtractionRepository;
import bme.mediai.recordingfinding.persistence.RecordingExtractionStatus;
import bme.mediai.recordingfinding.service.RecordingFindingService;
import bme.mediai.recordingsummary.persistence.RecordingSummary;
import bme.mediai.recordingsummary.persistence.RecordingSummaryRepository;
import bme.mediai.recordingsummary.persistence.RecordingSummaryStatus;
import bme.mediai.recordingsummary.service.RecordingSummaryService;
import bme.mediai.voiceprofile.persistence.VoiceProfile;
import bme.mediai.voiceprofile.persistence.VoiceProfileRepository;
import bme.mediai.voiceprofile.persistence.VoiceProfileStatus;
import bme.mediai.voiceprofile.service.VoiceProfileService;

// Félbemaradt hangszolgáltatás-feladatok (ai-service/voice leállt / újraindult: a sora csak memóriában él,
// a callback sosem jön). Induláskor és utána percenként, a Python /health "pending" listája alapján:
//  - felvétel: újra átadjuk (mint a dokumentumot, ld. DocumentProcessingResume) — a hang megmaradt, más
//    úton nem dolgozható fel újra. Csak a böngészőből feltöltött felvétel ({beteg}/hangfelvetelek/); a
//    CLI-ből kézzel importált (JSON) felvételen nem a Python dolgozik.
//  - hangprofil, összefoglaló, csoportok: 60 mp után FAILED — újrakérhetők (gombbal / új profillal).
// Idempotens: a Python a sorban állót nem veszi fel kétszer, a lezárás és a callback csak PROCESSING-ből ír.
// Ha a Python nem érhető el: a felvétel PROCESSING marad (a következő körben folytatódik), a többi FAILED.
@Component
public class VoiceLostJobs {

    private static final Logger LOG = LoggerFactory.getLogger(VoiceLostJobs.class);
    private static final Duration LOST_JOB_GRACE = Duration.ofSeconds(60);
    private static final String LOST = "A hangszolgáltatás (ai-service/voice) leállt vagy újraindult, a kérés elveszett";

    private final VoiceServiceClient client;
    private final RecordingRepository recordingRepository;
    private final VoiceProfileRepository profileRepository;
    private final RecordingSummaryRepository summaryRepository;
    private final RecordingExtractionRepository extractionRepository;
    private final RecordingProcessingService recordingProcessing;
    private final VoiceProfileService profileService;
    private final RecordingSummaryService summaryService;
    private final RecordingFindingService findingService;
    private final AudioStorage recordingStorage;
    private boolean unreachableLogged;

    public VoiceLostJobs(VoiceServiceClient client, RecordingRepository recordingRepository,
            VoiceProfileRepository profileRepository, RecordingSummaryRepository summaryRepository,
            RecordingExtractionRepository extractionRepository, RecordingProcessingService recordingProcessing,
            VoiceProfileService profileService, RecordingSummaryService summaryService,
            RecordingFindingService findingService, @Value("${mediai.storage.documents-dir}") Path documentsDir) {
        this.client = client;
        this.recordingRepository = recordingRepository;
        this.profileRepository = profileRepository;
        this.summaryRepository = summaryRepository;
        this.extractionRepository = extractionRepository;
        this.recordingProcessing = recordingProcessing;
        this.profileService = profileService;
        this.summaryService = summaryService;
        this.findingService = findingService;
        // ugyanúgy, mint a RecordingService: a felvételek a documents-dir alatt
        this.recordingStorage = new AudioStorage(documentsDir);
    }

    // Szándékosan NEM tranzakcióban: a HTTP-hívások alatt ne tartsunk nyitva adatbázis-kapcsolatot
    @Scheduled(initialDelay = 25, fixedDelay = 60, timeUnit = TimeUnit.SECONDS)
    public void handleLostJobs() {
        OffsetDateTime before = OffsetDateTime.now().minus(LOST_JOB_GRACE);
        List<Recording> recordings = recordingRepository.findByStatus(RecordingStatus.PROCESSING).stream()
                .filter(VoiceLostJobs::uploadedForProcessing)
                .toList();
        List<VoiceProfile> profiles = profileRepository.findByStatusAndCreatedAtBefore(VoiceProfileStatus.PROCESSING,
                before);
        List<RecordingSummary> summaries = summaryRepository.findByStatusAndRequestedAtBefore(
                RecordingSummaryStatus.PROCESSING, before);
        List<RecordingExtraction> extractions = extractionRepository.findByStatusAndRequestedAtBefore(
                RecordingExtractionStatus.PROCESSING, before);
        if (recordings.isEmpty() && profiles.isEmpty() && summaries.isEmpty() && extractions.isEmpty()) {
            return;
        }
        // régi Python-verzió (nincs "pending" a /health-ben): nem tudjuk, mi fut nála, ne nyúljunk hozzá
        Optional<Set<String>> pending = client.pendingJobs();
        if (pending.isEmpty()) {
            return;
        }
        Set<String> running = pending.get();
        int failed = 0;
        for (VoiceProfile p : profiles) {
            if (!running.contains("profile:" + p.getId())) {
                profileService.failEnrollment(p.getId(), LOST + " — vedd fel újra a hangprofilt");
                failed++;
            }
        }
        for (RecordingSummary s : summaries) {
            if (!running.contains("summary:" + s.getId())) {
                summaryService.fail(s.getId(), LOST + " — generáld újra az összefoglalót");
                failed++;
            }
        }
        for (RecordingExtraction e : extractions) {
            if (!running.contains("findings:" + e.getId())) {
                findingService.fail(e.getId(), LOST + " — kérd újra a csoportokat");
                failed++;
            }
        }
        if (failed > 0) {
            LOG.warn("{} beragadt hangszolgáltatás-feladat lezárva (FAILED)", failed);
        }
        resumeRecordings(recordings, running);
    }

    private void resumeRecordings(List<Recording> recordings, Set<String> running) {
        int resumed = 0;
        for (Recording r : recordings) {
            if (running.contains("recording:" + r.getId())) {
                continue;
            }
            // átadás előtt újraolvassuk: a közben elkészült felvétel ne fusson újra
            Optional<Recording> current = recordingRepository.findById(r.getId())
                    .filter(x -> x.getStatus() == RecordingStatus.PROCESSING);
            if (current.isEmpty()) {
                continue;
            }
            float[] embedding = Optional.ofNullable(current.get().getVoiceProfileId())
                    .flatMap(profileRepository::findById)
                    .map(VoiceProfile::getEmbedding)
                    .orElse(null);
            if (embedding == null) {
                recordingProcessing.fail(r.getId(), LOST + ", és a felvételkori hangprofil már nem elérhető");
                continue;
            }
            List<Float> doctorEmbedding = new ArrayList<>(embedding.length);
            for (float v : embedding) {
                doctorEmbedding.add(v);
            }
            try {
                client.submitRecording(new RecordingJobRequest(r.getId(),
                        recordingStorage.resolve(current.get().getAudioPath()).toString(),
                        current.get().getMode().name(), doctorEmbedding,
                        client.callbackUrl("/api/recordings/" + r.getId() + "/processing-result")));
                resumed++;
            } catch (RestClientException e) {
                if (!unreachableLogged) {
                    LOG.warn("A hangszolgáltatás nem érhető el, {} felvétel feldolgozása vár: {}",
                            recordings.size(), e.getMessage());
                    unreachableLogged = true;
                }
                return;
            }
        }
        unreachableLogged = false;
        if (resumed > 0) {
            LOG.info("{} félbemaradt felvétel-feldolgozás újra átadva a hangszolgáltatásnak", resumed);
        }
    }

    // A böngészőből feltöltött, megtartott hangú felvétel; a kézi (JSON) import útja tetszőleges kliens-szöveg
    private static boolean uploadedForProcessing(Recording r) {
        return r.getAudioPath() != null && r.getAudioDeletedAt() == null
                && r.getAudioPath().matches("\\d+/" + RecordingService.RECORDINGS_FOLDER + "/[^/]+");
    }
}
