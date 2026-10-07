package bme.mediai.summary.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.Patient;
import bme.mediai.patient.persistence.PatientRepository;
import bme.mediai.summary.api.ClinicalSummaryResponse;
import bme.mediai.summary.api.SummaryResultRequest;
import bme.mediai.summary.exception.ClinicalSummaryNotFoundException;
import bme.mediai.summary.exception.SummaryConflictException;
import bme.mediai.summary.persistence.ClinicalSummary;
import bme.mediai.summary.persistence.ClinicalSummaryRepository;
import bme.mediai.summary.persistence.SummaryStatus;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.UserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

// Klinikai státusz: AI-összefoglaló a beteg elmúlt 6 hónapjáról. A Spring gyűjti az adatot és
// tárolja az eredményt; az Ollamát a Python hívja (a Spring sosem). Külön tábla, nem statement —
// a felhasználó döntése (docs/clinical-summary.md).
@Service
public class ClinicalSummaryService {

    private static final ZoneId CLINICAL_ZONE = ZoneId.of("Europe/Budapest");
    private static final int PERIOD_MONTHS = 6;
    private static final int MAX_ERROR_LENGTH = 2000;

    private final ClinicalSummaryRepository repository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final SummaryFactsBuilder factsBuilder;
    private final ApplicationEventPublisher events;
    private final Validator validator;

    public ClinicalSummaryService(ClinicalSummaryRepository repository, PatientRepository patientRepository,
            UserRepository userRepository, SummaryFactsBuilder factsBuilder, ApplicationEventPublisher events,
            Validator validator) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.factsBuilder = factsBuilder;
        this.events = events;
        this.validator = validator;
    }

    @Transactional
    public ClinicalSummaryResponse request(Long patientId, Long userId) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new PatientNotFoundException(patientId));
        if (userId != null && !userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
        if (repository.existsByPatientIdAndStatus(patientId, SummaryStatus.PROCESSING)) {
            throw new SummaryConflictException("A betegnek már készül összefoglalója");
        }
        LocalDate end = LocalDate.now(CLINICAL_ZONE);
        LocalDate start = end.minusMonths(PERIOD_MONTHS);
        SummaryFactsBuilder.Built built = factsBuilder.build(patient, start, end);
        boolean empty = built.inputIds().values().stream().allMatch(List::isEmpty);
        if (empty) {
            throw new IllegalArgumentException("Az elmúlt " + PERIOD_MONTHS + " hónapból nincs összefoglalható adat");
        }
        ClinicalSummary saved;
        try {
            saved = repository.saveAndFlush(new ClinicalSummary(patientId, userId, start, end, built.inputIds()));
        } catch (DataIntegrityViolationException e) {
            // párhuzamos kérés: a részleges egyedi index (egy PROCESSING / beteg) fogta meg
            throw new SummaryConflictException("A betegnek már készül összefoglalója");
        }
        events.publishEvent(new SummaryRequestedEvent(saved.getId(), built.facts()));
        return ClinicalSummaryResponse.from(reload(saved.getId()), false);
    }

    @Transactional(readOnly = true)
    public List<ClinicalSummaryResponse> findByPatient(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
        List<ClinicalSummary> all = repository.findByPatientIdOrderByRequestedAtDescIdDesc(patientId);
        // "elavult" csak a legutóbbi elkészültre értelmes
        return all.stream()
                .map(s -> ClinicalSummaryResponse.from(s, s == all.getFirst() && s.getStatus() == SummaryStatus.DONE
                        && repository.hasNewDataSince(patientId, s.getRequestedAt())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ClinicalSummaryResponse findById(Long id) {
        return ClinicalSummaryResponse.from(reload(id), false);
    }

    // Python callback. Az érvénytelen eredmény is FAILED-del zárja (a controller hívja a fail-t)
    @Transactional
    public ClinicalSummaryResponse complete(Long id, SummaryResultRequest result) {
        ClinicalSummary summary = reload(id);
        if (summary.getStatus() != SummaryStatus.PROCESSING) {
            throw new SummaryConflictException("A(z) " + id + " összefoglaló eredménye már megérkezett");
        }
        Set<ConstraintViolation<SummaryResultRequest>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                    .collect(Collectors.joining("; ")));
        }
        if ("FAILED".equals(result.status())) {
            fail(id, "AI-feldolgozás: " + (result.error() == null ? "ismeretlen hiba" : result.error()));
            return ClinicalSummaryResponse.from(reload(id), false);
        }
        if (result.summary() == null || result.summary().isBlank() || result.model() == null) {
            throw new IllegalArgumentException("DONE eredményhez kötelező a summary és a model");
        }
        SummaryResultRequest.Model m = result.model();
        List<String> recs = result.recommendations() == null ? List.of() : result.recommendations();
        List<String> warns = result.warnings() == null ? List.of() : result.warnings();
        if (repository.complete(id, result.summary().strip(), recs.toArray(String[]::new), warns.toArray(String[]::new),
                m.modelName(), m.seed(), m.promptHash(), m.extractorVersion()) == 0) {
            throw new SummaryConflictException("A(z) " + id + " összefoglaló eredménye már megérkezett");
        }
        return ClinicalSummaryResponse.from(reload(id), false);
    }

    @Transactional
    public void fail(Long id, String error) {
        String message = error == null || error.isBlank() ? "Ismeretlen hiba" : error.strip();
        repository.markFailed(id, message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message);
    }

    private ClinicalSummary reload(Long id) {
        return repository.findById(id).orElseThrow(() -> new ClinicalSummaryNotFoundException(id));
    }
}
