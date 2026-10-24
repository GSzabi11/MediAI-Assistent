package bme.mediai.epicrisis.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentRepository;
import bme.mediai.epicrisis.api.EpicrisisResponse;
import bme.mediai.epicrisis.api.EpicrisisResultRequest;
import bme.mediai.epicrisis.exception.EpicrisisConflictException;
import bme.mediai.epicrisis.exception.EpicrisisNotFoundException;
import bme.mediai.epicrisis.persistence.Epicrisis;
import bme.mediai.epicrisis.persistence.EpicrisisRepository;
import bme.mediai.epicrisis.persistence.EpicrisisStatus;
import bme.mediai.medicalcare.exception.MedicalCareNotFoundException;
import bme.mediai.medicalcare.persistence.MedicalCare;
import bme.mediai.medicalcare.persistence.MedicalCareRepository;
import bme.mediai.medicalhistory.api.MedicalHistoryResultRequest;
import bme.mediai.medicalhistory.persistence.DocumentDigest;
import bme.mediai.medicalhistory.persistence.DocumentDigestRepository;
import bme.mediai.medicalhistory.service.MedicalHistoryDocx;
import bme.mediai.medicalhistory.service.MedicalHistoryFactsBuilder;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.Patient;
import bme.mediai.patient.persistence.PatientRepository;
import bme.mediai.processing.service.AiServiceClient;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.Role;
import bme.mediai.user.persistence.User;
import bme.mediai.user.persistence.UserRepository;
import bme.mediai.utterance.persistence.Utterance;
import bme.mediai.utterance.persistence.UtteranceRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

// Epikrízis egy ellátáshoz (docs/epicrisis.md). A Spring gyűjti az adatot (a kiválasztott leletek, az ellátás
// felvételei, gyógyszerek), tárolja az eredményt és a dokumentum-kivonatokat; az Ollamát a Python hívja.
// Külön tábla, nem statement — mint a kórtörténet.
@Service
public class EpicrisisService {

    private static final int MAX_ERROR_LENGTH = 2000;
    // a dispatch (commit után, legfeljebb ~12 mp újrapróbálással) ennyi idő alatt biztosan beadta a feladatot
    private static final Duration LOST_JOB_GRACE = Duration.ofSeconds(60);

    private final EpicrisisRepository repository;
    private final DocumentDigestRepository digestRepository;
    private final DocumentRepository documentRepository;
    private final MedicalCareRepository medicalCareRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final UtteranceRepository utteranceRepository;
    private final EpicrisisFactsBuilder factsBuilder;
    private final ApplicationEventPublisher events;
    private final Validator validator;
    private final AiServiceClient aiService;
    private final EpicrisisProgressStore progressStore;

    public EpicrisisService(EpicrisisRepository repository, DocumentDigestRepository digestRepository,
            DocumentRepository documentRepository, MedicalCareRepository medicalCareRepository,
            PatientRepository patientRepository, UserRepository userRepository,
            UtteranceRepository utteranceRepository, EpicrisisFactsBuilder factsBuilder,
            ApplicationEventPublisher events, Validator validator, AiServiceClient aiService,
            EpicrisisProgressStore progressStore) {
        this.repository = repository;
        this.digestRepository = digestRepository;
        this.documentRepository = documentRepository;
        this.medicalCareRepository = medicalCareRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.utteranceRepository = utteranceRepository;
        this.factsBuilder = factsBuilder;
        this.events = events;
        this.validator = validator;
        this.aiService = aiService;
        this.progressStore = progressStore;
    }

    // Az ellátás kiválasztásával: az ellátás dokumentumai + felvételei + az aktuális gyógyszerek
    @Transactional
    public EpicrisisResponse request(Long medicalCareId, Long userId) {
        MedicalCare care = medicalCareRepository.findById(medicalCareId)
                .orElseThrow(() -> new MedicalCareNotFoundException(medicalCareId));
        Patient patient = patient(care.getPatientId());
        if (userId != null && !userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
        if (repository.existsByMedicalCareIdAndStatus(medicalCareId, EpicrisisStatus.PROCESSING)) {
            throw new EpicrisisConflictException("Az ellátáshoz már készül epikrízis");
        }
        Set<Long> selected = careDocuments(patient.getId(), care);
        EpicrisisFactsBuilder.Built built = factsBuilder.build(patient, care, selected);
        if (built.inputIds().get("documentIds").isEmpty() && built.inputIds().get("recordingIds").isEmpty()) {
            throw new IllegalArgumentException("Nincs feldolgozott lelet vagy kész hangfelvétel-átirat, amiből az "
                    + "epikrízis elkészülhetne — az ellátás időszakában nincs lelet, és nincs az ellátáshoz rendelt kész felvétel");
        }
        Epicrisis saved;
        try {
            saved = repository.saveAndFlush(new Epicrisis(patient.getId(), medicalCareId, userId, built.medications(),
                    built.inputIds()));
        } catch (DataIntegrityViolationException e) {
            // párhuzamos kérés: a részleges egyedi index (egy PROCESSING / ellátás) fogta meg
            throw new EpicrisisConflictException("Az ellátáshoz már készül epikrízis");
        }
        events.publishEvent(new EpicrisisRequestedEvent(saved.getId(), built.facts()));
        return EpicrisisResponse.from(saved, EpicrisisHtml.header(patient, care), null);
    }

    // Az ellátáshoz tartozó dokumentumok: a használható (feldolgozott, típussal és dátummal rendelkező) leletek,
    // amelyek klinikai dátuma az ellátás időszakába esik (EpicrisisFactsBuilder.inCarePeriod). Tartós lelet ↔
    // ellátás hozzárendelés még nincs (docs/epicrisis.md).
    private Set<Long> careDocuments(Long patientId, MedicalCare care) {
        return documentRepository.findByPatientIdOrderByUploadedAtDesc(patientId).stream()
                .filter(d -> MedicalHistoryFactsBuilder.usable(d) && EpicrisisFactsBuilder.inCarePeriod(d, care))
                .map(Document::getId)
                .collect(Collectors.toSet());
    }

    // Beragadt feladat (a docproc leállt / újraindult): ld. MedicalHistoryService.failLostJobs.
    // Szándékosan NEM tranzakcióban: a HTTP-hívás alatt ne tartsunk nyitva adatbázis-kapcsolatot.
    public void failLostJobs(Long patientId) {
        List<Epicrisis> processing = repository.findByPatientIdAndStatusAndRequestedAtBefore(patientId,
                EpicrisisStatus.PROCESSING, OffsetDateTime.now().minus(LOST_JOB_GRACE));
        if (processing.isEmpty()) {
            return;
        }
        aiService.pendingJobs().ifPresent(pending -> processing.stream()
                .filter(e -> !pending.contains("epicrisis:" + e.getId()))
                .forEach(e -> {
                    repository.markFailedIfProcessing(e.getId(), "A feldolgozó (ai-service/docproc) leállt vagy "
                            + "újraindult, a kérés elveszett — indítsd újra a generálást");
                    progressStore.remove(e.getId());
                }));
    }

    // Python → Spring: a készülő epikrízis aktuális lépése; lezártnál figyelmen kívül marad
    @Transactional(readOnly = true)
    public void progress(Long id, String message, Integer step, Integer total) {
        if (reload(id).getStatus() == EpicrisisStatus.PROCESSING) {
            progressStore.put(id, message, step, total);
        }
    }

    // a beteg összes epikrízise (minden ellátásé), a legfrissebb elöl — a felület ellátásonként csoportosít
    @Transactional(readOnly = true)
    public List<EpicrisisResponse> findByPatient(Long patientId) {
        Patient patient = patient(patientId);
        Map<Long, MedicalCare> cares = medicalCareRepository.findByPatientIdOrderByStartedAtDesc(patientId).stream()
                .collect(Collectors.toMap(MedicalCare::getId, Function.identity()));
        return repository.findByPatientIdOrderByRequestedAtDescIdDesc(patientId).stream()
                .map(e -> EpicrisisResponse.from(e, EpicrisisHtml.header(patient, cares.get(e.getMedicalCareId())),
                        progressStore.get(e.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public EpicrisisResponse findById(Long id) {
        return response(reload(id));
    }

    // Python callback. Az érvénytelen eredmény is FAILED-del zárja (a controller hívja a fail-t)
    @Transactional
    public EpicrisisResponse complete(Long id, EpicrisisResultRequest result) {
        Epicrisis epicrisis = reload(id);
        if (epicrisis.getStatus() != EpicrisisStatus.PROCESSING) {
            throw new EpicrisisConflictException("A(z) " + id + " epikrízis eredménye már megérkezett");
        }
        progressStore.remove(id);
        Set<ConstraintViolation<EpicrisisResultRequest>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                    .collect(Collectors.joining("; ")));
        }
        if ("FAILED".equals(result.status())) {
            epicrisis.fail(truncate("AI-feldolgozás: " + (result.error() == null ? "ismeretlen hiba" : result.error())));
            return response(epicrisis);
        }
        List<EpicrisisResultRequest.Finding> findings = nonNull(result.findings());
        if (findings.isEmpty() || result.model() == null) {
            throw new IllegalArgumentException("DONE eredményhez kötelező legalább egy megállapítás és a model");
        }

        // MERRE — identitás-kapu: minden hivatkozás csak a modellnek átadott leletre / felvétel létező
        // megszólalására mutathat
        Set<Long> documentIds = new HashSet<>(epicrisis.getInput().get("documentIds"));
        Set<Long> recordingIds = new HashSet<>(epicrisis.getInput().get("recordingIds"));
        List<EpicrisisResultRequest.Diagnosis> diagnoses = nonNull(result.diagnoses());
        for (EpicrisisResultRequest.Diagnosis d : diagnoses) {
            requireSource(documentIds, recordingIds, d.documentId(), d.recordingId(), d.turnIds());
        }
        for (EpicrisisResultRequest.Finding f : findings) {
            if (f.documentId() != null || f.recordingId() != null) {
                requireSource(documentIds, recordingIds, f.documentId(), f.recordingId(), f.turnIds());
            } else if (f.flag() == null) {
                throw new IllegalArgumentException("Forrás nélküli megállapítás csak ellenőrizendő jelöléssel jöhet");
            }
        }
        List<MedicalHistoryResultRequest.Digest> digests = nonNull(result.digests());
        for (MedicalHistoryResultRequest.Digest d : digests) {
            if (!documentIds.contains(d.documentId())) {
                throw new IllegalArgumentException("A(z) " + d.documentId() + " dokumentum nem volt az epikrízis bemenete");
            }
        }
        saveDigests(epicrisis.getPatientId(), digests);

        MedicalHistoryResultRequest.Model m = result.model();
        try {
            epicrisis.complete(
                    diagnoses.stream().map(d -> new Epicrisis.Diagnosis(d.name().strip(), blankToNull(d.bnoCode()),
                            d.documentId(), d.recordingId(), nonNull(d.turnIds()))).toList(),
                    findings.stream().map(f -> new Epicrisis.Finding(f.text().strip(), f.documentId(), f.recordingId(),
                            nonNull(f.turnIds()), blankToNull(f.quote()), blankToNull(f.flag()))).toList(),
                    nonNull(result.recommendations()), nonNull(result.warnings()),
                    m.modelName(), m.seed(), m.promptHash(), m.extractorVersion());
        } catch (IllegalStateException e) {
            throw new EpicrisisConflictException(e.getMessage());
        }
        return response(epicrisis);
    }

    private void requireSource(Set<Long> documentIds, Set<Long> recordingIds, Long documentId, Long recordingId,
            List<Integer> turnIds) {
        if (documentId == null && recordingId == null) {
            throw new IllegalArgumentException("A diagnózis forrása (lelet vagy megszólalás) kötelező");
        }
        if (documentId != null && !documentIds.contains(documentId)) {
            throw new IllegalArgumentException("A(z) " + documentId + " dokumentum nem volt az epikrízis bemenete");
        }
        if (recordingId != null) {
            if (!recordingIds.contains(recordingId)) {
                throw new IllegalArgumentException("A(z) " + recordingId + " felvétel nem volt az epikrízis bemenete");
            }
            // üres turnIds: az orvos kézzel felvett csoportelem, amely nem hangzott el szó szerint — a forrás a felvétel
            List<Integer> turns = nonNull(turnIds);
            if (!turns.isEmpty() && utteranceRepository.findByRecordingIdAndTurnIdInOrderByTurnId(recordingId, turns)
                    .stream().map(Utterance::getTurnId).distinct().count() != turns.stream().distinct().count()) {
                throw new IllegalArgumentException("A(z) " + recordingId + " felvételnek nincs ilyen megszólalása: "
                        + turns);
            }
        }
    }

    // A kivonat dokumentumonként egy (a kórtörténettel közös): az új a régit lecseréli
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

    // Az orvos szerkesztése (csak DOCTOR, csak kész epikrízis). A HTML-t a Spring szűri; a fejléc nem része.
    @Transactional
    public EpicrisisResponse edit(Long id, Long userId, String html) {
        Epicrisis epicrisis = reload(id);
        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getRole() != Role.DOCTOR) {
            throw new EpicrisisConflictException("Epikrízist csak orvos (DOCTOR) szerkeszthet");
        }
        String clean = EpicrisisHtml.sanitize(html);
        if (EpicrisisHtml.isBlank(clean)) {
            throw new IllegalArgumentException("A szerkesztett szöveg nem lehet üres");
        }
        try {
            epicrisis.edit(clean, userId);
        } catch (IllegalStateException e) {
            throw new EpicrisisConflictException(e.getMessage());
        }
        return response(epicrisis);
    }

    public record Export(String fileName, byte[] content) {
    }

    // DOCX a sablon szerint: EPIKRÍZIS, fejléc (DB), a törzs (az orvosé, ha szerkesztette), lábléc
    @Transactional(readOnly = true)
    public Export export(Long id) {
        Epicrisis epicrisis = reload(id);
        if (epicrisis.getStatus() != EpicrisisStatus.DONE) {
            throw new EpicrisisConflictException("Csak kész epikrízis exportálható");
        }
        Patient patient = patient(epicrisis.getPatientId());
        MedicalCare care = care(epicrisis.getMedicalCareId());
        EpicrisisHtml.Header header = EpicrisisHtml.header(patient, care);
        boolean edited = epicrisis.getEditedHtml() != null;
        byte[] docx = MedicalHistoryDocx.fromHtml("<h1>EPIKRÍZIS</h1>" + EpicrisisHtml.renderHeader(header) + "<hr>"
                + EpicrisisHtml.effective(epicrisis)
                + "<hr><p class=\"note\"><i>"
                + (edited ? "AI által előkészített, orvos által szerkesztett epikrízis."
                        : "AI által előkészített epikrízis — orvosi ellenőrzés nélkül.")
                + " Döntéstámogatásra; a végső döntés az orvosé.</i></p>");
        String name = patient.getName() == null ? "beteg" : patient.getName().strip().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return new Export("Epikrizis_" + name + "_" + header.date().replace(". ", "-").replace(".", "") + ".docx",
                docx);
    }

    @Transactional
    public void fail(Long id, String error) {
        progressStore.remove(id);
        repository.findById(id).ifPresent(e -> e.fail(truncate(error == null || error.isBlank()
                ? "Ismeretlen hiba" : error.strip())));
    }

    private EpicrisisResponse response(Epicrisis e) {
        return EpicrisisResponse.from(e, EpicrisisHtml.header(patient(e.getPatientId()), care(e.getMedicalCareId())),
                progressStore.get(e.getId()));
    }

    private Patient patient(Long patientId) {
        return patientRepository.findById(patientId).orElseThrow(() -> new PatientNotFoundException(patientId));
    }

    private MedicalCare care(Long medicalCareId) {
        return medicalCareRepository.findById(medicalCareId)
                .orElseThrow(() -> new MedicalCareNotFoundException(medicalCareId));
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

    private Epicrisis reload(Long id) {
        return repository.findById(id).orElseThrow(() -> new EpicrisisNotFoundException(id));
    }
}
