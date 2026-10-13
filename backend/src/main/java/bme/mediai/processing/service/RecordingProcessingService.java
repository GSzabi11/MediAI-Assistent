package bme.mediai.processing.service;

import bme.mediai.keyword.service.KeywordService;
import bme.mediai.recording.api.RecordingResponse;
import bme.mediai.recording.api.RecordingResultRequest;
import bme.mediai.recording.api.RecordingResultRequest.Turn;
import bme.mediai.recording.exception.RecordingConflictException;
import bme.mediai.recording.exception.RecordingNotFoundException;
import bme.mediai.recording.persistence.Recording;
import bme.mediai.recording.persistence.RecordingRepository;
import bme.mediai.recording.persistence.RecordingStatus;
import bme.mediai.recording.service.RecordingTranscribedEvent;
import bme.mediai.utterance.persistence.Utterance;
import bme.mediai.utterance.persistence.UtteranceRepository;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// A Python hangszolgáltatás eredményének rögzítése (docs/voice-module.md). Egy callback = egy
// tranzakció: a megszólalások egyben kerülnek be (vagy egyik sem), és a felvétel DONE lesz. Az eredeti
// hangfelvétel megmarad (recording.audio_path) — a felhasználó döntése: visszahallgatható, újra feldolgozható.
@Service
public class RecordingProcessingService {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final RecordingRepository recordingRepository;
    private final UtteranceRepository utteranceRepository;
    private final KeywordService keywordService;
    private final ApplicationEventPublisher events;
    private final Validator validator;

    public RecordingProcessingService(RecordingRepository recordingRepository,
            UtteranceRepository utteranceRepository, KeywordService keywordService,
            ApplicationEventPublisher events, Validator validator) {
        this.recordingRepository = recordingRepository;
        this.utteranceRepository = utteranceRepository;
        this.keywordService = keywordService;
        this.events = events;
        this.validator = validator;
    }

    @Transactional
    public RecordingResponse complete(Long id, RecordingResultRequest result) {
        Recording recording = recordingRepository.findById(id).orElseThrow(() -> new RecordingNotFoundException(id));
        if (recording.getStatus() != RecordingStatus.PROCESSING) {
            throw new RecordingConflictException("A(z) " + id + " felvétel feldolgozásának eredménye már megérkezett");
        }
        Set<ConstraintViolation<RecordingResultRequest>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                    .collect(Collectors.joining("; ")));
        }
        if ("FAILED".equals(result.status())) {
            failRecording(recording, "Hangfeldolgozás: " + (result.error() == null ? "ismeretlen hiba" : result.error()));
            return RecordingResponse.from(recording);
        }
        if (result.asrModel() == null || result.asrModel().isBlank()) {
            throw new IllegalArgumentException("DONE eredményhez kötelező az asrModel");
        }
        List<Turn> turns = result.utterances() == null ? List.of() : result.utterances();
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("A felvételben nem volt felismerhető beszéd");
        }
        if (utteranceRepository.existsByRecordingId(id)) {
            throw new RecordingConflictException("A(z) " + id + " felvétel megszólalásai már be vannak töltve");
        }
        Set<Integer> turnIds = new HashSet<>();
        for (Turn t : turns) {
            if (!turnIds.add(t.turnId())) {
                throw new IllegalArgumentException("Ismétlődő turnId: " + t.turnId());
            }
            if (t.endMs() < t.startMs()) {
                throw new IllegalArgumentException("A(z) " + t.turnId() + " megszólalás vége a kezdete előtt van");
            }
        }
        String reviewModel = result.reviewModel() == null || result.reviewModel().isBlank()
                ? null : result.reviewModel().strip();
        utteranceRepository.saveAll(turns.stream()
                .map(t -> Utterance.reviewed(id, t.turnId(), t.speaker(), t.similarity(), t.startMs(), t.endMs(),
                        t.text().strip(), t.asrText() == null ? null : t.asrText().strip(), t.asrSpeaker(),
                        reviewModel, t.correctionReason(), t.reviewNote()))
                .toList());

        recording.completeProcessing(result.asrModel().strip(), reviewModel,
                result.warnings() == null ? List.of() : result.warnings());
        keywordService.addModelSuggestions(id, result.keywords(),
                result.keywordModel() == null || result.keywordModel().isBlank() ? null : result.keywordModel().strip());
        recordingRepository.flush();
        // commit után: az első összefoglaló a teljes átiratból + az AI kulcsszavaiból
        events.publishEvent(new RecordingTranscribedEvent(id));
        return RecordingResponse.from(recording);
    }

    @Transactional
    public void fail(Long id, String error) {
        recordingRepository.findById(id).ifPresent(r -> {
            if (r.getStatus() == RecordingStatus.PROCESSING) {
                failRecording(r, error);
            }
        });
    }

    private void failRecording(Recording recording, String error) {
        String message = error == null || error.isBlank() ? "Ismeretlen hiba" : error.strip();
        recording.failProcessing(message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message);
    }
}
