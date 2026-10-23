package bme.mediai.research.service;

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
import bme.mediai.research.api.ResearchContextResponse;
import bme.mediai.research.api.ResearchContextResultRequest;
import bme.mediai.research.exception.ResearchConflictException;
import bme.mediai.research.exception.ResearchNotFoundException;
import bme.mediai.research.persistence.ResearchContext;
import bme.mediai.research.persistence.ResearchContextRepository;
import bme.mediai.research.persistence.ResearchContextStatus;
import bme.mediai.summary.service.SummaryFactsBuilder;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.Role;
import bme.mediai.user.persistence.User;
import bme.mediai.user.persistence.UserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

// A beteg állapotának összefoglalója a kutatáshoz. Ugyanazokból a tényekből, mint a Klinikai státusz
// (SummaryFactsBuilder, név/TAJ nélkül); a Python helyi modellje írja, az orvos átírhatja.
@Service
public class ResearchContextService {

    private static final ZoneId CLINICAL_ZONE = ZoneId.of("Europe/Budapest");
    // hosszabb, mint a Klinikai státusz 6 hónapja: a kutatásnál a régebbi diagnózis is számít
    private static final int PERIOD_MONTHS = 24;
    private static final int MAX_ERROR_LENGTH = 2000;

    private final ResearchContextRepository repository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final SummaryFactsBuilder factsBuilder;
    private final ApplicationEventPublisher events;
    private final Validator validator;

    public ResearchContextService(ResearchContextRepository repository, PatientRepository patientRepository,
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
    public ResearchContextResponse request(Long patientId, Long userId) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new PatientNotFoundException(patientId));
        if (userId != null && !userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
        if (repository.existsByPatientIdAndStatus(patientId, ResearchContextStatus.PROCESSING)) {
            throw new ResearchConflictException("A betegnek már készül kutatási összefoglalója");
        }
        LocalDate end = LocalDate.now(CLINICAL_ZONE);
        SummaryFactsBuilder.Built built = factsBuilder.build(patient, end.minusMonths(PERIOD_MONTHS), end);
        if (built.inputIds().values().stream().allMatch(List::isEmpty)) {
            throw new IllegalArgumentException("Az elmúlt " + PERIOD_MONTHS
                    + " hónapból nincs összefoglalható adat — írd be az összefoglalót kézzel");
        }
        ResearchContext saved;
        try {
            saved = repository.saveAndFlush(new ResearchContext(patientId, userId, built.inputIds()));
        } catch (DataIntegrityViolationException e) {
            throw new ResearchConflictException("A betegnek már készül kutatási összefoglalója");
        }
        events.publishEvent(new ResearchEvents.ContextRequested(saved.getId(), built.facts()));
        return ResearchContextResponse.from(reload(saved.getId()));
    }

    @Transactional(readOnly = true)
    public List<ResearchContextResponse> findByPatient(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
        return repository.findByPatientIdOrderByRequestedAtDescIdDesc(patientId).stream()
                .map(ResearchContextResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ResearchContextResponse findById(Long id) {
        return ResearchContextResponse.from(reload(id));
    }

    // Az orvos szerkesztése (csak DOCTOR, csak DONE). Az AI szövege megmarad.
    @Transactional
    public ResearchContextResponse edit(Long id, Long userId, String text, String query) {
        ResearchContext context = reload(id);
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getRole() != Role.DOCTOR) {
            throw new ResearchConflictException("A kutatási összefoglalót csak orvos (DOCTOR) szerkesztheti");
        }
        try {
            context.edit(text, query, userId);
        } catch (IllegalStateException e) {
            throw new ResearchConflictException(e.getMessage());
        }
        return ResearchContextResponse.from(context);
    }

    // Python callback; az érvénytelen eredmény is FAILED-del zárja (a controller hívja a fail-t)
    @Transactional
    public ResearchContextResponse complete(Long id, ResearchContextResultRequest result) {
        ResearchContext context = reload(id);
        if (context.getStatus() != ResearchContextStatus.PROCESSING) {
            throw new ResearchConflictException("A(z) " + id + " összefoglaló eredménye már megérkezett");
        }
        Set<ConstraintViolation<ResearchContextResultRequest>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                    .collect(Collectors.joining("; ")));
        }
        if ("FAILED".equals(result.status())) {
            context.fail(truncate("AI-feldolgozás: " + (result.error() == null ? "ismeretlen hiba" : result.error())));
            return ResearchContextResponse.from(context);
        }
        if (result.summary() == null || result.summary().isBlank() || result.query() == null
                || result.query().isBlank() || result.model() == null) {
            throw new IllegalArgumentException("DONE eredményhez kötelező a summary, a query és a model");
        }
        ResearchContextResultRequest.Model m = result.model();
        context.complete(result.summary().strip(), result.query().strip(),
                result.warnings() == null ? List.of() : result.warnings(), m.modelName(), m.seed(), m.promptHash(),
                m.extractorVersion());
        return ResearchContextResponse.from(context);
    }

    @Transactional
    public void fail(Long id, String error) {
        repository.findById(id).ifPresent(c -> c.fail(truncate(error == null || error.isBlank()
                ? "Ismeretlen hiba" : error.strip())));
    }

    private ResearchContext reload(Long id) {
        return repository.findById(id).orElseThrow(() -> new ResearchNotFoundException("kutatási összefoglaló", id));
    }

    private static String truncate(String message) {
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}
