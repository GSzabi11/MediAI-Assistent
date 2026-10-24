package bme.mediai.recordingfinding.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bme.mediai.processing.service.VoiceServiceClient;
import bme.mediai.recording.exception.RecordingNotFoundException;
import bme.mediai.recording.persistence.Recording;
import bme.mediai.recording.persistence.RecordingRepository;
import bme.mediai.recording.persistence.RecordingStatus;
import bme.mediai.recordingfinding.api.ExtractionJobRequest;
import bme.mediai.recordingfinding.api.ExtractionResponse;
import bme.mediai.recordingfinding.api.ExtractionResultRequest;
import bme.mediai.recordingfinding.api.FindingCreateRequest;
import bme.mediai.recordingfinding.api.FindingFact;
import bme.mediai.recordingfinding.api.FindingResponse;
import bme.mediai.recordingfinding.api.FindingsResponse;
import bme.mediai.recordingfinding.exception.RecordingFindingConflictException;
import bme.mediai.recordingfinding.exception.RecordingFindingNotFoundException;
import bme.mediai.recordingfinding.persistence.RecordingExtraction;
import bme.mediai.recordingfinding.persistence.RecordingExtractionRepository;
import bme.mediai.recordingfinding.persistence.RecordingExtractionStatus;
import bme.mediai.recordingfinding.persistence.RecordingFinding;
import bme.mediai.recordingfinding.persistence.RecordingFindingRepository;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.Role;
import bme.mediai.user.persistence.User;
import bme.mediai.user.persistence.UserRepository;
import bme.mediai.utterance.persistence.Utterance;
import bme.mediai.utterance.persistence.UtteranceRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

// A felvétel csoportosított elemei (panaszok, diagnózisok, …). A Spring gyűjti az átiratot és tárolja az
// eredményt; az Ollamát a Python hangszolgáltatás hívja. Az orvos hozzáad, szerkeszt, töröl — az AI
// eredetije megmarad.
@Service
public class RecordingFindingService {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final RecordingFindingRepository repository;
    private final RecordingExtractionRepository extractionRepository;
    private final RecordingRepository recordingRepository;
    private final UtteranceRepository utteranceRepository;
    private final UserRepository userRepository;
    private final VoiceServiceClient voiceService;
    private final ApplicationEventPublisher events;
    private final Validator validator;

    public RecordingFindingService(RecordingFindingRepository repository,
            RecordingExtractionRepository extractionRepository, RecordingRepository recordingRepository,
            UtteranceRepository utteranceRepository, UserRepository userRepository, VoiceServiceClient voiceService,
            ApplicationEventPublisher events, Validator validator) {
        this.repository = repository;
        this.extractionRepository = extractionRepository;
        this.recordingRepository = recordingRepository;
        this.utteranceRepository = utteranceRepository;
        this.userRepository = userRepository;
        this.voiceService = voiceService;
        this.events = events;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public FindingsResponse findByRecording(Long recordingId) {
        if (!recordingRepository.existsById(recordingId)) {
            throw new RecordingNotFoundException(recordingId);
        }
        ExtractionResponse latest = extractionRepository.findFirstByRecordingIdOrderByRequestedAtDescIdDesc(recordingId)
                .map(ExtractionResponse::from).orElse(null);
        Long current = extractionRepository.findFirstByRecordingIdAndStatusOrderByRequestedAtDescIdDesc(recordingId,
                RecordingExtractionStatus.DONE).map(RecordingExtraction::getId).orElse(null);
        List<FindingResponse> items = repository.findCurrent(recordingId, current).stream()
                .map(FindingResponse::from).toList();
        return new FindingsResponse(latest, current, items);
    }

    // Az érvényes elemek (ld. findByRecording) az orvos szerkesztett állapotában — az összefoglaló és az
    // epikrízis bemenete. Ha még nincs kész futás, csak az orvos elemei.
    @Transactional(readOnly = true)
    public List<FindingFact> currentFacts(Long recordingId) {
        Long current = extractionRepository.findFirstByRecordingIdAndStatusOrderByRequestedAtDescIdDesc(recordingId,
                RecordingExtractionStatus.DONE).map(RecordingExtraction::getId).orElse(null);
        return repository.findCurrent(recordingId, current).stream().map(FindingFact::from).toList();
    }

    /** userId null: automatikus kérés a felvétel elkészültekor. */
    @Transactional
    public ExtractionResponse request(Long recordingId, Long userId) {
        Recording recording = requireDoneRecording(recordingId);
        if (userId != null) {
            requireDoctor(userId);
        }
        List<Utterance> utterances = utteranceRepository.findByRecordingIdOrderByTurnId(recordingId);
        if (utterances.isEmpty()) {
            throw new RecordingFindingConflictException("A felvételnek nincs átirata");
        }
        if (extractionRepository.existsByRecordingIdAndStatus(recordingId, RecordingExtractionStatus.PROCESSING)) {
            throw new RecordingFindingConflictException("A felvételből már folyamatban van a kinyerés");
        }
        RecordingExtraction saved;
        try {
            saved = extractionRepository.saveAndFlush(new RecordingExtraction(recordingId, userId));
        } catch (DataIntegrityViolationException e) {
            // párhuzamos kérés: a részleges egyedi index (egy PROCESSING / felvétel) fogta meg
            throw new RecordingFindingConflictException("A felvételből már folyamatban van a kinyerés");
        }
        events.publishEvent(new RecordingExtractionRequestedEvent(new ExtractionJobRequest(
                saved.getId(),
                recording.getMode().name(),
                utterances.stream()
                        .map(u -> new ExtractionJobRequest.Turn(u.getTurnId(), u.getSpeaker().name(), u.getText()))
                        .toList(),
                voiceService.callbackUrl("/api/recording-extractions/" + saved.getId() + "/result"))));
        return ExtractionResponse.from(saved);
    }

    // Python callback. Az érvénytelen eredmény is FAILED-del zárja (a controller hívja a fail-t)
    @Transactional
    public ExtractionResponse complete(Long id, ExtractionResultRequest result) {
        RecordingExtraction extraction = extractionRepository.findById(id)
                .orElseThrow(() -> new RecordingFindingNotFoundException("kinyerés", id));
        if (extraction.getStatus() != RecordingExtractionStatus.PROCESSING) {
            throw new RecordingFindingConflictException("A(z) " + id + " kinyerés eredménye már megérkezett");
        }
        Set<ConstraintViolation<ExtractionResultRequest>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                    .collect(Collectors.joining("; ")));
        }
        if ("FAILED".equals(result.status())) {
            extraction.fail(truncate("AI-feldolgozás: " + (result.error() == null ? "ismeretlen hiba" : result.error())));
            return ExtractionResponse.from(extraction);
        }
        if (result.model() == null) {
            throw new IllegalArgumentException("DONE eredményhez kötelező a model");
        }
        // MERRE: a hivatkozott megszólalások létezzenek (a Python is ellenőrzi; itt a forrás az adatbázis)
        Set<Integer> turnIds = existingTurnIds(extraction.getRecordingId());
        List<ExtractionResultRequest.Item> items = result.items() == null ? List.of() : result.items();
        for (ExtractionResultRequest.Item item : items) {
            List<Integer> refs = item.turnIds().stream().distinct().sorted().toList();
            requireTurnIds(refs, turnIds);
            repository.save(RecordingFinding.fromModel(extraction.getRecordingId(), extraction.getId(),
                    item.category(), item.text(), item.bnoCode(), refs,
                    item.flag() == null || item.flag().isBlank() ? null : item.flag().strip()));
        }
        ExtractionResultRequest.Model m = result.model();
        extraction.complete(result.warnings() == null ? List.of() : result.warnings(), m.modelName(), m.seed(),
                m.promptHash());
        settled(extraction);
        return ExtractionResponse.from(extraction);
    }

    @Transactional
    public FindingResponse add(Long recordingId, FindingCreateRequest request) {
        requireDoneRecording(recordingId);
        requireDoctor(request.userId());
        List<Integer> refs = Optional.ofNullable(request.turnIds()).orElse(List.of()).stream()
                .distinct().sorted().toList();
        requireTurnIds(refs, existingTurnIds(recordingId));
        RecordingFinding finding = RecordingFinding.addedByDoctor(recordingId, request.category(), request.text(),
                request.bnoCode(), refs, request.userId());
        return FindingResponse.from(repository.saveAndFlush(finding));
    }

    @Transactional
    public FindingResponse edit(Long recordingId, Long id, Long userId, String text, String bnoCode) {
        RecordingFinding finding = findOfRecording(recordingId, id);
        requireDoctor(userId);
        try {
            finding.edit(text, bnoCode, userId);
        } catch (IllegalStateException e) {
            throw new RecordingFindingConflictException(e.getMessage());
        }
        return FindingResponse.from(finding);
    }

    @Transactional
    public FindingResponse delete(Long recordingId, Long id, Long userId) {
        RecordingFinding finding = findOfRecording(recordingId, id);
        requireDoctor(userId);
        try {
            finding.delete(userId);
        } catch (IllegalStateException e) {
            throw new RecordingFindingConflictException(e.getMessage());
        }
        return FindingResponse.from(finding);
    }

    @Transactional
    public void fail(Long id, String error) {
        extractionRepository.findById(id).filter(e -> e.getStatus() == RecordingExtractionStatus.PROCESSING)
                .ifPresent(e -> {
                    e.fail(truncate(error == null || error.isBlank() ? "Ismeretlen hiba" : error.strip()));
                    settled(e);
                });
    }

    // Az automatikus (a felvétel elkészültekor indult) futás vége indítja az első összefoglalót; az orvos kérte
    // újrakinyerés nem (az összefoglaló gombbal újragenerálható)
    private void settled(RecordingExtraction extraction) {
        if (extraction.getRequestedBy() == null) {
            events.publishEvent(new RecordingFindingsSettledEvent(extraction.getRecordingId()));
        }
    }

    private RecordingFinding findOfRecording(Long recordingId, Long id) {
        return repository.findById(id)
                .filter(f -> f.getRecordingId().equals(recordingId))
                .orElseThrow(() -> new RecordingFindingNotFoundException("elem", id));
    }

    private Set<Integer> existingTurnIds(Long recordingId) {
        return utteranceRepository.findByRecordingIdOrderByTurnId(recordingId).stream()
                .map(Utterance::getTurnId).collect(Collectors.toSet());
    }

    private static void requireTurnIds(List<Integer> refs, Set<Integer> existing) {
        for (Integer t : refs) {
            if (!existing.contains(t)) {
                throw new IllegalArgumentException("Nem létező megszólalásra hivatkozik: " + t);
            }
        }
    }

    private Recording requireDoneRecording(Long recordingId) {
        Recording recording = recordingRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingNotFoundException(recordingId));
        if (recording.getStatus() != RecordingStatus.DONE) {
            throw new RecordingFindingConflictException("Csak kész (átírt) felvételhez");
        }
        return recording;
    }

    private void requireDoctor(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getRole() != Role.DOCTOR) {
            throw new RecordingFindingConflictException("A csoportokat csak orvos (DOCTOR) szerkesztheti");
        }
    }

    private static String truncate(String message) {
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
