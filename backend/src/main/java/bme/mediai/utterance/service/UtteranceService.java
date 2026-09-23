package bme.mediai.utterance.service;

import bme.mediai.recording.exception.RecordingNotFoundException;
import bme.mediai.recording.persistence.Recording;
import bme.mediai.recording.persistence.RecordingRepository;
import bme.mediai.recording.persistence.RecordingStatus;
import bme.mediai.utterance.api.UtteranceRequest;
import bme.mediai.utterance.api.UtteranceResponse;
import bme.mediai.utterance.exception.RecordingNotProcessingException;
import bme.mediai.utterance.exception.UtterancesAlreadyExistException;
import bme.mediai.utterance.persistence.Utterance;
import bme.mediai.utterance.persistence.UtteranceRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class UtteranceService {

    private final UtteranceRepository repository;
    private final RecordingRepository recordingRepository;

    public UtteranceService(UtteranceRepository repository, RecordingRepository recordingRepository) {
        this.repository = repository;
        this.recordingRepository = recordingRepository;
    }

    @Transactional
    public List<UtteranceResponse> createAll(Long recordingId, List<UtteranceRequest> requests) {
        Recording recording = recordingRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingNotFoundException(recordingId));

        if (recording.getStatus() != RecordingStatus.PROCESSING) {
            throw new RecordingNotProcessingException(recordingId, recording.getStatus());
        }
        if (repository.existsByRecordingId(recordingId)) {
            throw new UtterancesAlreadyExistException(recordingId);
        }
        validateBatch(requests);

        List<Utterance> utterances = requests.stream()
                .map(r -> new Utterance(
                        recordingId,
                        r.turnId(),
                        r.speaker(),
                        r.similarity(),
                        r.startMs(),
                        r.endMs(),
                        r.text(),
                        r.asrConfidence()))
                .toList();

        return repository.saveAll(utterances)
                .stream()
                .map(UtteranceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UtteranceResponse> findByRecording(Long recordingId) {
        if (!recordingRepository.existsById(recordingId)) {
            throw new RecordingNotFoundException(recordingId);
        }
        return repository.findByRecordingIdOrderByTurnId(recordingId)
                .stream()
                .map(UtteranceResponse::from)
                .toList();
    }

    // A DB is kikényszeríti (UNIQUE, CHECK), de akkor 500 lenne belőle; itt 400 + érthető üzenet.
    private void validateBatch(List<UtteranceRequest> requests) {
        Set<Integer> turnIds = new HashSet<>();
        for (UtteranceRequest r : requests) {
            if (!turnIds.add(r.turnId())) {
                throw new IllegalArgumentException("Ismétlődő turnId a kérésben: " + r.turnId());
            }
            if (r.endMs() < r.startMs()) {
                throw new IllegalArgumentException(
                        "A(z) " + r.turnId() + " megszólalás endMs értéke kisebb a startMs-nél");
            }
        }
    }
}
