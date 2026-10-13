package bme.mediai.keyword.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bme.mediai.keyword.api.KeywordResponse;
import bme.mediai.keyword.exception.KeywordConflictException;
import bme.mediai.keyword.exception.KeywordNotFoundException;
import bme.mediai.keyword.persistence.KeywordStatus;
import bme.mediai.keyword.persistence.RecordingKeyword;
import bme.mediai.keyword.persistence.RecordingKeywordRepository;
import bme.mediai.recording.exception.RecordingNotFoundException;
import bme.mediai.recording.persistence.Recording;
import bme.mediai.recording.persistence.RecordingRepository;
import bme.mediai.recording.persistence.RecordingStatus;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.Role;
import bme.mediai.user.persistence.User;
import bme.mediai.user.persistence.UserRepository;

// A felvétel kulcsszavai: az AI javaslatai (a feldolgozás callbackjéből) és az orvos + gombbal
// hozzáadott kifejezései. A kiemelés az átiratban a frontenden, szövegegyezéssel történik.
@Service
public class KeywordService {

    // A modell javaslataiból ennyi kerül be (a többi zaj lenne a kiemelésben)
    public static final int MAX_MODEL_KEYWORDS = 15;

    private final RecordingKeywordRepository repository;
    private final RecordingRepository recordingRepository;
    private final UserRepository userRepository;

    public KeywordService(RecordingKeywordRepository repository, RecordingRepository recordingRepository,
            UserRepository userRepository) {
        this.repository = repository;
        this.recordingRepository = recordingRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<KeywordResponse> findByRecording(Long recordingId) {
        if (!recordingRepository.existsById(recordingId)) {
            throw new RecordingNotFoundException(recordingId);
        }
        return repository.findByRecordingIdOrderByCreatedAtAscIdAsc(recordingId).stream()
                .map(KeywordResponse::from).toList();
    }

    @Transactional
    public KeywordResponse addByDoctor(Long recordingId, Long userId, String term) {
        requireDoneRecording(recordingId);
        requireDoctor(userId);
        RecordingKeyword keyword = RecordingKeyword.addedByDoctor(recordingId, term, userId);
        if (repository.existsTerm(recordingId, keyword.getTerm())) {
            throw new KeywordConflictException("Ez a kulcsszó már szerepel a felvételnél: " + keyword.getTerm());
        }
        try {
            return KeywordResponse.from(repository.saveAndFlush(keyword));
        } catch (DataIntegrityViolationException e) {
            throw new KeywordConflictException("Ez a kulcsszó már szerepel a felvételnél: " + keyword.getTerm());
        }
    }

    @Transactional
    public KeywordResponse decide(Long recordingId, Long keywordId, Long userId, KeywordStatus decision) {
        RecordingKeyword keyword = repository.findById(keywordId)
                .filter(k -> k.getRecordingId().equals(recordingId))
                .orElseThrow(() -> new KeywordNotFoundException(keywordId));
        requireDoctor(userId);
        try {
            keyword.decide(decision, userId);
        } catch (IllegalStateException e) {
            throw new KeywordConflictException(e.getMessage());
        }
        return KeywordResponse.from(keyword);
    }

    /**
     * Az AI javaslatai a feldolgozás callbackjéből (ugyanabban a tranzakcióban). Ismétlődő és üres
     * kifejezés kimarad; a Python már ellenőrizte, hogy mindegyik elhangzott az átiratban.
     */
    @Transactional
    public void addModelSuggestions(Long recordingId, List<String> terms, String modelName) {
        if (terms == null || terms.isEmpty() || modelName == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (String raw : terms) {
            if (seen.size() >= MAX_MODEL_KEYWORDS || raw == null) {
                continue;
            }
            String term = raw.strip().replaceAll("\\s+", " ");
            if (term.isEmpty() || term.length() > RecordingKeyword.MAX_TERM_LENGTH
                    || !seen.add(term.toLowerCase(Locale.ROOT))) {
                continue;
            }
            repository.save(RecordingKeyword.suggestedByModel(recordingId, term, modelName));
        }
    }

    /** Az összefoglalóhoz: az elfogadott és a még el nem döntött kulcsszavak (az elutasított nem). */
    @Transactional(readOnly = true)
    public List<String> activeTerms(Long recordingId) {
        return repository.findByRecordingIdOrderByCreatedAtAscIdAsc(recordingId).stream()
                .filter(k -> k.getStatus() != KeywordStatus.REJECTED)
                .map(RecordingKeyword::getTerm)
                .toList();
    }

    private void requireDoneRecording(Long recordingId) {
        Recording recording = recordingRepository.findById(recordingId)
                .orElseThrow(() -> new RecordingNotFoundException(recordingId));
        if (recording.getStatus() != RecordingStatus.DONE) {
            throw new KeywordConflictException("Kulcsszó csak kész (átírt) felvételhez adható");
        }
    }

    private void requireDoctor(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getRole() != Role.DOCTOR) {
            throw new KeywordConflictException("Kulcsszót csak orvos (DOCTOR) adhat hozzá vagy hagyhat jóvá");
        }
    }
}
