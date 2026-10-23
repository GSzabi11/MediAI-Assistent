package bme.mediai.medicalhistory.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bme.mediai.medicalhistory.api.MedicalHistoryResponse;
import bme.mediai.medicalhistory.api.MedicalHistoryResultRequest;
import bme.mediai.medicalhistory.exception.MedicalHistoryConflictException;
import bme.mediai.medicalhistory.exception.MedicalHistoryNotFoundException;
import bme.mediai.medicalhistory.persistence.DocumentDigest;
import bme.mediai.medicalhistory.persistence.DocumentDigestRepository;
import bme.mediai.medicalhistory.persistence.MedicalHistory;
import bme.mediai.medicalhistory.persistence.MedicalHistoryRepository;
import bme.mediai.medicalhistory.persistence.MedicalHistoryStatus;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.Patient;
import bme.mediai.patient.persistence.PatientRepository;
import bme.mediai.processing.service.AiServiceClient;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.Role;
import bme.mediai.user.persistence.User;
import bme.mediai.user.persistence.UserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

// Kórtörténet (Medical history): anamnézis + kórtörténeti összefoglaló, idővonal, diagnózisok, gyógyszerek,
// javaslatok. A Spring gyűjti az adatot, tárolja az eredményt és a dokumentum-kivonatokat; az Ollamát a
// Python hívja. Külön tábla, nem statement — a felhasználó döntése (docs/medical-history.md).
@Service
public class MedicalHistoryService {

    private static final ZoneId CLINICAL_ZONE = ZoneId.of("Europe/Budapest");
    private static final int MAX_ERROR_LENGTH = 2000;
    // a dispatch (commit után, legfeljebb ~12 mp újrapróbálással) ennyi idő alatt biztosan beadta a feladatot
    private static final Duration LOST_JOB_GRACE = Duration.ofSeconds(60);

    private final MedicalHistoryRepository repository;
    private final DocumentDigestRepository digestRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final MedicalHistoryFactsBuilder factsBuilder;
    private final ApplicationEventPublisher events;
    private final Validator validator;
    private final AiServiceClient aiService;
    private final MedicalHistoryProgressStore progressStore;

    public MedicalHistoryService(MedicalHistoryRepository repository, DocumentDigestRepository digestRepository,
            PatientRepository patientRepository, UserRepository userRepository,
            MedicalHistoryFactsBuilder factsBuilder, ApplicationEventPublisher events, Validator validator,
            AiServiceClient aiService, MedicalHistoryProgressStore progressStore) {
        this.aiService = aiService;
        this.progressStore = progressStore;
        this.repository = repository;
        this.digestRepository = digestRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.factsBuilder = factsBuilder;
        this.events = events;
        this.validator = validator;
    }

    @Transactional
    public MedicalHistoryResponse request(Long patientId, Long userId) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new PatientNotFoundException(patientId));
        if (userId != null && !userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
        if (repository.existsByPatientIdAndStatus(patientId, MedicalHistoryStatus.PROCESSING)) {
            throw new MedicalHistoryConflictException("A betegnek már készül kórtörténeti összefoglalója");
        }
        MedicalHistoryFactsBuilder.Built built = factsBuilder.build(patient);
        if (built.inputIds().get("documentIds").isEmpty()) {
            throw new IllegalArgumentException("A betegnek nincs feldolgozott dokumentuma, amiből kórtörténet készülhetne");
        }
        MedicalHistory saved;
        try {
            saved = repository.saveAndFlush(new MedicalHistory(patientId, userId, built.medications(),
                    built.inputIds()));
        } catch (DataIntegrityViolationException e) {
            // párhuzamos kérés: a részleges egyedi index (egy PROCESSING / beteg) fogta meg
            throw new MedicalHistoryConflictException("A betegnek már készül kórtörténeti összefoglalója");
        }
        events.publishEvent(new MedicalHistoryRequestedEvent(saved.getId(), built.facts()));
        return MedicalHistoryResponse.from(saved, false);
    }

    // Beragadt feladat felismerése (a felület 5 mp-enként kérdez, amíg készül): ha a docproc leállt vagy
    // újraindult, a sora (csak memóriában) elveszett, a callback sosem jön, és a "Készül…" örökre maradna.
    // A dispatch utáni türelmi idő után megkérdezzük a Pythont; ha nála nincs ez a feladat (vagy nem
    // érhető el), FAILED az okkal — a gomb újra használható. Szándékosan NEM tranzakcióban: a HTTP-hívás
    // alatt ne tartsunk nyitva adatbázis-kapcsolatot.
    public void failLostJobs(Long patientId) {
        List<MedicalHistory> processing = repository.findByPatientIdAndStatusAndRequestedAtBefore(patientId,
                MedicalHistoryStatus.PROCESSING, OffsetDateTime.now().minus(LOST_JOB_GRACE));
        if (processing.isEmpty()) {
            return;
        }
        aiService.pendingJobs().ifPresent(pending -> processing.stream()
                .filter(h -> !pending.contains("medical-history:" + h.getId()))
                .forEach(h -> {
                    repository.markFailedIfProcessing(h.getId(), "A feldolgozó (ai-service/docproc) "
                            + "leállt vagy újraindult, a kérés elveszett — indítsd újra a generálást");
                    progressStore.remove(h.getId());
                }));
    }

    // Python → Spring: a készülő kórtörténet aktuális lépése; lezárt kórtörténetnél figyelmen kívül marad
    @Transactional(readOnly = true)
    public void progress(Long id, String message, Integer step, Integer total) {
        if (reload(id).getStatus() == MedicalHistoryStatus.PROCESSING) {
            progressStore.put(id, message, step, total);
        }
    }

    @Transactional(readOnly = true)
    public List<MedicalHistoryResponse> findByPatient(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
        List<MedicalHistory> all = repository.findByPatientIdOrderByRequestedAtDescIdDesc(patientId);
        // "elavult" csak a legutóbbi elkészültre értelmes
        return all.stream()
                .map(h -> MedicalHistoryResponse.from(h, h == all.getFirst() && h.getStatus() == MedicalHistoryStatus.DONE
                        && repository.hasNewDataSince(patientId, h.getRequestedAt()), progressStore.get(h.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public MedicalHistoryResponse findById(Long id) {
        return MedicalHistoryResponse.from(reload(id), false);
    }

    // Python callback. Az érvénytelen eredmény is FAILED-del zárja (a controller hívja a fail-t)
    @Transactional
    public MedicalHistoryResponse complete(Long id, MedicalHistoryResultRequest result) {
        MedicalHistory history = reload(id);
        if (history.getStatus() != MedicalHistoryStatus.PROCESSING) {
            throw new MedicalHistoryConflictException("A(z) " + id + " kórtörténet eredménye már megérkezett");
        }
        progressStore.remove(id);
        Set<ConstraintViolation<MedicalHistoryResultRequest>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                    .collect(Collectors.joining("; ")));
        }
        if ("FAILED".equals(result.status())) {
            history.fail(truncate("AI-feldolgozás: " + (result.error() == null ? "ismeretlen hiba" : result.error())));
            return MedicalHistoryResponse.from(history, false);
        }
        if (result.summary() == null || result.summary().isBlank() || result.model() == null) {
            throw new IllegalArgumentException("DONE eredményhez kötelező a summary és a model");
        }

        // Minden hivatkozás csak a modellnek átadott dokumentumra mutathat (MERRE — identitás-kapu)
        Set<Long> documentIds = new HashSet<>(history.getInput().get("documentIds"));
        List<MedicalHistoryResultRequest.TimelineItem> timeline = nonNull(result.timeline());
        List<MedicalHistoryResultRequest.Diagnosis> diagnoses = nonNull(result.diagnoses());
        List<MedicalHistoryResultRequest.Digest> digests = nonNull(result.digests());
        for (Long ref : timeline.stream().map(MedicalHistoryResultRequest.TimelineItem::documentId).toList()) {
            requireInput(documentIds, ref);
        }
        for (Long ref : diagnoses.stream().map(MedicalHistoryResultRequest.Diagnosis::documentId).toList()) {
            requireInput(documentIds, ref);
        }
        for (Long ref : digests.stream().map(MedicalHistoryResultRequest.Digest::documentId).toList()) {
            requireInput(documentIds, ref);
        }
        saveDigests(history.getPatientId(), digests);

        MedicalHistoryResultRequest.Model m = result.model();
        try {
            history.complete(result.summary().strip(),
                    timeline.stream().map(t -> new MedicalHistory.TimelineItem(t.date(), t.title().strip(),
                            blankToNull(t.text()), blankToNull(t.place()), t.documentId(), t.kind(),
                            blankToNull(t.flag()))).toList(),
                    diagnoses.stream().map(d -> new MedicalHistory.Diagnosis(d.name().strip(), blankToNull(d.bnoCode()),
                            blankToNull(d.date()), d.documentId())).toList(),
                    nonNull(result.recommendations()), nonNull(result.warnings()),
                    m.modelName(), m.seed(), m.promptHash(), m.extractorVersion());
        } catch (IllegalStateException e) {
            throw new MedicalHistoryConflictException(e.getMessage());
        }
        return MedicalHistoryResponse.from(history, false);
    }

    // A kivonat dokumentumonként egy: az új (promptváltás miatt újraszámolt) a régit lecseréli
    private void saveDigests(Long patientId, List<MedicalHistoryResultRequest.Digest> digests) {
        if (digests.isEmpty()) {
            return;
        }
        digestRepository.deleteByDocumentIds(digests.stream().map(MedicalHistoryResultRequest.Digest::documentId)
                .toList());
        digestRepository.saveAll(digests.stream().map(d -> new DocumentDigest(d.documentId(), patientId,
                d.title().strip(), blankToNull(d.place()), d.summary().strip(), blankToNull(d.quote()),
                d.events().stream().map(e -> new DocumentDigest.Event(e.date(), e.title(), e.quote())).toList(),
                d.diagnoses().stream().map(x -> new DocumentDigest.Diagnosis(x.name(), blankToNull(x.bnoCode()),
                        blankToNull(x.since()), x.quote())).toList(),
                d.warnings(), d.model().modelName(), d.model().seed(), d.model().promptHash(),
                d.model().extractorVersion())).toList());
    }

    // Az orvos szerkesztése (csak DOCTOR, csak kész kórtörténet). A HTML-t a Spring szűri.
    @Transactional
    public MedicalHistoryResponse edit(Long id, Long userId, String html) {
        MedicalHistory history = reload(id);
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getRole() != Role.DOCTOR) {
            throw new MedicalHistoryConflictException("Kórtörténetet csak orvos (DOCTOR) szerkeszthet");
        }
        String clean = MedicalHistoryHtml.sanitize(html);
        if (MedicalHistoryHtml.isBlank(clean)) {
            throw new IllegalArgumentException("A szerkesztett szöveg nem lehet üres");
        }
        try {
            history.edit(clean, userId);
        } catch (IllegalStateException e) {
            throw new MedicalHistoryConflictException(e.getMessage());
        }
        return MedicalHistoryResponse.from(history, false);
    }

    public record Export(String fileName, byte[] content) {
    }

    // DOCX: a beteg fejléce (név, TAJ, születési dátum) + a megjelenített szöveg (az orvosé, ha szerkesztette)
    @Transactional(readOnly = true)
    public Export export(Long id) {
        MedicalHistory history = reload(id);
        if (history.getStatus() != MedicalHistoryStatus.DONE) {
            throw new MedicalHistoryConflictException("Csak kész kórtörténet exportálható");
        }
        Patient patient = patientRepository.findById(history.getPatientId())
                .orElseThrow(() -> new PatientNotFoundException(history.getPatientId()));
        boolean edited = history.getEditedHtml() != null;
        LocalDate madeOn = (edited ? history.getEditedAt() : history.getCompletedAt())
                .atZoneSameInstant(CLINICAL_ZONE).toLocalDate();
        byte[] docx = MedicalHistoryDocx.build(patient, MedicalHistoryHtml.effective(history), madeOn, edited);
        String name = patient.getName() == null ? "beteg" : patient.getName().strip().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return new Export("Medical_history_" + name + "_" + madeOn + ".docx", docx);
    }

    @Transactional
    public void fail(Long id, String error) {
        progressStore.remove(id);
        repository.findById(id).ifPresent(h -> h.fail(truncate(error == null || error.isBlank()
                ? "Ismeretlen hiba" : error.strip())));
    }

    private static void requireInput(Set<Long> documentIds, Long ref) {
        if (!documentIds.contains(ref)) {
            throw new IllegalArgumentException("A(z) " + ref + " dokumentum nem volt a kórtörténet bemenete");
        }
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String truncate(String message) {
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }

    private MedicalHistory reload(Long id) {
        return repository.findById(id).orElseThrow(() -> new MedicalHistoryNotFoundException(id));
    }
}
