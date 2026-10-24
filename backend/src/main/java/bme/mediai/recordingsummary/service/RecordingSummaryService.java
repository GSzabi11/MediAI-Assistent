package bme.mediai.recordingsummary.service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bme.mediai.keyword.service.KeywordService;
import bme.mediai.processing.service.VoiceServiceClient;
import bme.mediai.recording.exception.RecordingNotFoundException;
import bme.mediai.recording.persistence.Recording;
import bme.mediai.recording.persistence.RecordingRepository;
import bme.mediai.recording.persistence.RecordingStatus;
import bme.mediai.recordingfinding.service.RecordingFindingService;
import bme.mediai.recordingsummary.api.RecordingSummaryJobRequest;
import bme.mediai.recordingsummary.api.RecordingSummaryResponse;
import bme.mediai.recordingsummary.api.RecordingSummaryResultRequest;
import bme.mediai.recordingsummary.exception.RecordingSummaryConflictException;
import bme.mediai.recordingsummary.exception.RecordingSummaryNotFoundException;
import bme.mediai.recordingsummary.persistence.RecordingSummary;
import bme.mediai.recordingsummary.persistence.RecordingSummaryRepository;
import bme.mediai.recordingsummary.persistence.RecordingSummaryStatus;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.Role;
import bme.mediai.user.persistence.User;
import bme.mediai.user.persistence.UserRepository;
import bme.mediai.utterance.persistence.Utterance;
import bme.mediai.utterance.persistence.UtteranceRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

// A felvétel összefoglalója a teljes átiratból, a (nem elutasított) kulcsszavakból és a csoportok elemeiből. A Spring
// gyűjti az adatot és tárolja az eredményt; az Ollamát a Python hangszolgáltatás hívja.
@Service
public class RecordingSummaryService {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final RecordingSummaryRepository repository;
    private final RecordingRepository recordingRepository;
    private final UtteranceRepository utteranceRepository;
    private final UserRepository userRepository;
    private final KeywordService keywordService;
    private final RecordingFindingService findingService;
    private final VoiceServiceClient voiceService;
    private final ApplicationEventPublisher events;
    private final Validator validator;

    public RecordingSummaryService(RecordingSummaryRepository repository, RecordingRepository recordingRepository,
            UtteranceRepository utteranceRepository, UserRepository userRepository, KeywordService keywordService,
            RecordingFindingService findingService, VoiceServiceClient voiceService, ApplicationEventPublisher events,
            Validator validator) {
        this.repository = repository;
        this.recordingRepository = recordingRepository;
        this.utteranceRepository = utteranceRepository;
        this.userRepository = userRepository;
        this.keywordService = keywordService;
        this.findingService = findingService;
        this.voiceService = voiceService;
        this.events = events;
        this.validator = validator;
    }

    /** userId null: automatikus kérés a felvétel elkészültekor. */
    @Transactional
    public RecordingSummaryResponse request(Long recordingId, Long userId) {
        Recording recording = recordingRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingNotFoundException(recordingId));
        if (userId != null && !userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
        if (recording.getStatus() != RecordingStatus.DONE) {
            throw new RecordingSummaryConflictException("Összefoglaló csak kész (átírt) felvételből készülhet");
        }
        List<Utterance> utterances = utteranceRepository.findByRecordingIdOrderByTurnId(recordingId);
        if (utterances.isEmpty()) {
            throw new RecordingSummaryConflictException("A felvételnek nincs átirata");
        }
        if (repository.existsByRecordingIdAndStatus(recordingId, RecordingSummaryStatus.PROCESSING)) {
            throw new RecordingSummaryConflictException("A felvételnek már készül összefoglalója");
        }
        List<String> keywords = keywordService.activeTerms(recordingId);
        RecordingSummary saved;
        try {
            saved = repository.saveAndFlush(new RecordingSummary(recordingId, userId, keywords));
        } catch (DataIntegrityViolationException e) {
            // párhuzamos kérés: a részleges egyedi index (egy PROCESSING / felvétel) fogta meg
            throw new RecordingSummaryConflictException("A felvételnek már készül összefoglalója");
        }
        events.publishEvent(new RecordingSummaryRequestedEvent(new RecordingSummaryJobRequest(
                saved.getId(),
                recording.getMode().name(),
                utterances.stream()
                        .map(u -> new RecordingSummaryJobRequest.Turn(u.getTurnId(), u.getSpeaker().name(), u.getText()))
                        .toList(),
                keywords,
                findingService.currentFacts(recordingId),
                voiceService.callbackUrl("/api/recording-summaries/" + saved.getId() + "/result"))));
        return RecordingSummaryResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<RecordingSummaryResponse> findByRecording(Long recordingId) {
        if (!recordingRepository.existsById(recordingId)) {
            throw new RecordingNotFoundException(recordingId);
        }
        return repository.findByRecordingIdOrderByRequestedAtDescIdDesc(recordingId).stream()
                .map(RecordingSummaryResponse::from).toList();
    }

    // Python callback. Az érvénytelen eredmény is FAILED-del zárja (a controller hívja a fail-t)
    @Transactional
    public RecordingSummaryResponse complete(Long id, RecordingSummaryResultRequest result) {
        RecordingSummary summary = repository.findById(id).orElseThrow(() -> new RecordingSummaryNotFoundException(id));
        if (summary.getStatus() != RecordingSummaryStatus.PROCESSING) {
            throw new RecordingSummaryConflictException("A(z) " + id + " összefoglaló eredménye már megérkezett");
        }
        Set<ConstraintViolation<RecordingSummaryResultRequest>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                    .collect(Collectors.joining("; ")));
        }
        if ("FAILED".equals(result.status())) {
            summary.fail(truncate("AI-feldolgozás: " + (result.error() == null ? "ismeretlen hiba" : result.error())));
            return RecordingSummaryResponse.from(summary);
        }
        if (result.summary() == null || result.summary().isBlank() || result.model() == null) {
            throw new IllegalArgumentException("DONE eredményhez kötelező a summary és a model");
        }
        // MERRE: a hivatkozott megszólalások létezzenek (a Python is ellenőrzi; itt a forrás az adatbázis)
        Set<Integer> turnIds = utteranceRepository.findByRecordingIdOrderByTurnId(summary.getRecordingId()).stream()
                .map(Utterance::getTurnId).collect(Collectors.toSet());
        List<RecordingSummary.Point> points = (result.points() == null ? List.<RecordingSummaryResultRequest.Point>of()
                : result.points()).stream()
                .map(p -> new RecordingSummary.Point(p.text().strip(),
                        p.turnIds().stream().distinct().sorted().toList()))
                .toList();
        for (RecordingSummary.Point p : points) {
            for (Integer t : p.turnIds()) {
                if (!turnIds.contains(t)) {
                    throw new IllegalArgumentException("Az összefoglaló nem létező megszólalásra hivatkozik: " + t);
                }
            }
        }
        RecordingSummaryResultRequest.Model m = result.model();
        summary.complete(result.summary().strip(), points, result.warnings() == null ? List.of() : result.warnings(),
                m.modelName(), m.seed(), m.promptHash());
        return RecordingSummaryResponse.from(summary);
    }

    // Az orvos szerkesztése (csak DOCTOR, csak DONE). Az AI szövege megmarad, a válasz mindkettőt adja.
    @Transactional
    public RecordingSummaryResponse edit(Long recordingId, Long id, Long userId, String text) {
        RecordingSummary summary = repository.findById(id)
                .filter(s -> s.getRecordingId().equals(recordingId))
                .orElseThrow(() -> new RecordingSummaryNotFoundException(id));
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getRole() != Role.DOCTOR) {
            throw new RecordingSummaryConflictException("Összefoglalót csak orvos (DOCTOR) szerkeszthet");
        }
        try {
            summary.edit(text, userId);
        } catch (IllegalStateException e) {
            throw new RecordingSummaryConflictException(e.getMessage());
        }
        return RecordingSummaryResponse.from(summary);
    }

    @Transactional
    public void fail(Long id, String error) {
        repository.findById(id).ifPresent(s -> s.fail(truncate(error == null || error.isBlank()
                ? "Ismeretlen hiba" : error.strip())));
    }

    private static String truncate(String message) {
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
