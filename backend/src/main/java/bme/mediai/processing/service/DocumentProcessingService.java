package bme.mediai.processing.service;

import bme.mediai.allergy.service.AllergyService;
import bme.mediai.document.api.DocumentResponse;
import bme.mediai.document.exception.DocumentNotFoundException;
import bme.mediai.document.exception.DocumentNotProcessingException;
import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentRepository;
import bme.mediai.document.persistence.DocumentStatus;
import bme.mediai.document.persistence.DocumentType;
import bme.mediai.document.service.DocumentChunkService;
import bme.mediai.document.service.DocumentService;
import bme.mediai.lab.api.LabBatchRequest;
import bme.mediai.lab.service.LabResultService;
import bme.mediai.medication.service.MedicationService;
import bme.mediai.processing.api.ProcessingOutcome;
import bme.mediai.processing.api.ProcessingResultRequest;
import bme.mediai.processing.api.ProcessingResultRequest.ExtractedStatement;
import bme.mediai.statement.api.StatementCreateRequest;
import bme.mediai.statement.persistence.StatementProducer;
import bme.mediai.statement.persistence.StatementSource;
import bme.mediai.statement.persistence.StatementType;
import bme.mediai.statement.service.StatementService;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// A Python-feldolgozás eredményének rögzítése. Egy callback = egy tranzakció: ha bármelyik
// lépés elbukik (rossz offset, érvénytelen érték), semmi sem kerül be félig, és a hívó
// (controller) a dokumentumot külön tranzakcióban FAILED-re állítja.
@Service
public class DocumentProcessingService {

    private static final int MAX_ERROR_LENGTH = 2000;

    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final DocumentChunkService chunkService;
    private final LabResultService labResultService;
    private final StatementService statementService;
    private final MedicationService medicationService;
    private final AllergyService allergyService;
    private final Validator validator;

    public DocumentProcessingService(DocumentRepository documentRepository, DocumentService documentService,
            DocumentChunkService chunkService, LabResultService labResultService,
            StatementService statementService, MedicationService medicationService,
            AllergyService allergyService, Validator validator) {
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.chunkService = chunkService;
        this.labResultService = labResultService;
        this.statementService = statementService;
        this.medicationService = medicationService;
        this.allergyService = allergyService;
        this.validator = validator;
    }

    @Transactional
    public DocumentResponse complete(Long documentId, ProcessingResultRequest result) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.getStatus() != DocumentStatus.PROCESSING) {
            throw new DocumentNotProcessingException(documentId, document.getStatus());
        }
        validate(result);

        if (result.status() == ProcessingOutcome.FAILED) {
            requireThat(notBlank(result.error()), "FAILED eredményhez kötelező az error");
            markFailed(documentId, "AI-feldolgozás: " + result.error());
            return reload(documentId);
        }

        requireCompleteSuccess(result);
        DocumentType type = result.documentType();
        ProcessingResultRequest.Extraction extraction = result.extraction();
        List<String> warnings = new ArrayList<>(result.warnings() == null ? List.of() : result.warnings());

        // 1. Szöveg + besorolás: ettől kezdve a chunkok és állítások hivatkozhatnak a pozíciókra
        if (documentRepository.beginCompletion(documentId, result.extractedText(), type.name(),
                result.clinicalDate(), extraction.modelName(), extraction.seed(), extraction.promptHash(),
                extraction.extractorVersion()) == 0) {
            throw new DocumentNotProcessingException(documentId, document.getStatus());
        }

        // 2. RAG-darabok (minden típusnál) — a szöveget a Spring vágja ki a pozíciókból
        chunkService.createAll(documentId, result.chunks().embeddingModel(), result.chunks().chunks());

        // 3. Típusfüggő adatok
        switch (type) {
            case LAB -> storeLabResults(document, result, warnings);
            case AMBULANCE -> storeStatements(document, result, warnings);
            default -> throw new IllegalArgumentException("Nem automatikusan feldolgozható típus: " + type);
        }

        // 4. Végleges hely + állapot (a fájl törlése/visszagörgetése a tranzakció kimenetéhez kötött)
        String storagePath = documentService.relocateToTypeFolder(document, type);
        if (documentRepository.finishProcessing(documentId, storagePath, warnings.toArray(String[]::new)) == 0) {
            throw new DocumentNotProcessingException(documentId, document.getStatus());
        }
        return reload(documentId);
    }

    // Külön tranzakció (a complete() visszagörgetése után hívható): csak PROCESSING-ből vált
    @Transactional
    public void fail(Long documentId, String error) {
        markFailed(documentId, error);
    }

    private void markFailed(Long documentId, String error) {
        String message = error == null || error.isBlank() ? "Ismeretlen hiba" : error.strip();
        documentRepository.markFailed(documentId,
                message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message);
    }

    private void storeLabResults(Document document, ProcessingResultRequest result, List<String> warnings) {
        requireThat(result.statements() == null || result.statements().isEmpty(),
                "LAB dokumentumhoz nem tartozhatnak állítások (statements)");
        if (result.labResults() == null || result.labResults().isEmpty()) {
            warnings.add("A laborleletből egyetlen numerikus eredmény sem volt rögzíthető");
            return;
        }
        // measuredAt hiányában a dokumentum (most beállított) klinikai dátuma, budapesti éjfél
        labResultService.createAll(new LabBatchRequest(document.getPatientId(), document.getId(), result.labResults()));
    }

    private void storeStatements(Document document, ProcessingResultRequest result, List<String> warnings) {
        requireThat(result.labResults() == null || result.labResults().isEmpty(),
                "AMBULANCE dokumentumhoz nem tartozhatnak laborsorok (labResults)");
        if (result.statements() == null || result.statements().isEmpty()) {
            warnings.add("Az ambuláns lapból egyetlen állítás sem volt kinyerhető");
            return;
        }
        ProcessingResultRequest.Extraction extraction = result.extraction();
        StatementProducer producer = new StatementProducer.Model(
                extraction.modelName(), extraction.seed(), extraction.promptHash(), null);

        // Determinisztikus sorrend: a szövegbeli előfordulás szerint
        List<ExtractedStatement> ordered = result.statements().stream()
                .sorted(Comparator.comparing(ExtractedStatement::charStart).thenComparing(ExtractedStatement::charEnd))
                .toList();
        for (ExtractedStatement s : ordered) {
            requireThat(s.medication() == null || s.allergy() == null,
                    "Egy állítás nem lehet egyszerre gyógyszer és allergia: \"" + s.text() + "\"");

            // A StatementService ellenőrzi a beteget, a tartományt a kinyert szövegen, és a
            // MODEL_INFERENCE invariánsait (bizonytalanság, producer); clinicalTime = a lelet dátuma
            Long statementId = statementService.createFromApi(new StatementCreateRequest(
                    document.getPatientId(), StatementType.MODEL_INFERENCE, s.text(), s.label(), s.severity(),
                    s.uncertainty(), new StatementSource.Document(document.getId(), s.charStart(), s.charEnd()),
                    producer, null)).id();

            if (s.medication() != null) {
                var m = s.medication();
                if (medicationService.createFromPipeline(document.getPatientId(), m.name(), m.activeIngredient(),
                        m.dose(), m.doseUnit(), m.route(), statementId).isEmpty()) {
                    warnings.add("Gyógyszer már aktívan szerepel a listán, nem vettük fel újra: " + m.name().strip());
                }
            }
            if (s.allergy() != null
                    && allergyService.createFromPipeline(document.getPatientId(), s.allergy().substance(), statementId)
                            .isEmpty()) {
                warnings.add("Allergia már szerepel a listán, nem vettük fel újra: " + s.allergy().substance().strip());
            }
        }
    }

    // A @Valid-ot itt futtatjuk, nem a controllerben: egy érvénytelen eredmény is a dokumentum
    // FAILED állapotába kell torkolljon, különben örökre PROCESSING maradna.
    private void validate(ProcessingResultRequest result) {
        Set<ConstraintViolation<ProcessingResultRequest>> violations = validator.validate(result);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
    }

    private static void requireCompleteSuccess(ProcessingResultRequest r) {
        requireThat(r.documentType() == DocumentType.LAB || r.documentType() == DocumentType.AMBULANCE,
                "PROCESSED eredményhez a documentType LAB vagy AMBULANCE kell legyen");
        requireThat(notBlank(r.extractedText()), "PROCESSED eredményhez kötelező az extractedText");
        requireThat(r.clinicalDate() != null,
                "PROCESSED eredményhez kötelező a clinicalDate (a dokumentum szövegéből)");
        requireThat(r.extraction() != null, "PROCESSED eredményhez kötelező az extraction (modell, seed, prompt-hash)");
        requireThat(r.chunks() != null && r.chunks().chunks() != null && !r.chunks().chunks().isEmpty(),
                "PROCESSED eredményhez kötelezőek a RAG-darabok (chunks)");
    }

    private DocumentResponse reload(Long documentId) {
        return DocumentResponse.from(documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId)));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void requireThat(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
