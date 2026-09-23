package bme.mediai.lab.service;

import bme.mediai.document.exception.DocumentNotFoundException;
import bme.mediai.document.persistence.Document;
import bme.mediai.document.persistence.DocumentRepository;
import bme.mediai.lab.api.LabBatchRequest;
import bme.mediai.lab.api.LabResultRequest;
import bme.mediai.lab.api.LabResultResponse;
import bme.mediai.lab.exception.LabDocumentPatientMismatchException;
import bme.mediai.lab.exception.LabResultNotFoundException;
import bme.mediai.lab.persistence.LabResult;
import bme.mediai.lab.persistence.LabResultRepository;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.PatientRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class LabResultService {

    // A dokumentum klinikai dátuma (DATE) ebben a zónában éjfélkor lesz measuredAt
    private static final ZoneId CLINICAL_ZONE = ZoneId.of("Europe/Budapest");

    private final LabResultRepository repository;
    private final PatientRepository patientRepository;
    private final DocumentRepository documentRepository;

    public LabResultService(LabResultRepository repository, PatientRepository patientRepository,
            DocumentRepository documentRepository) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.documentRepository = documentRepository;
    }

    // Egy panel egy tranzakcióban: ha bármelyik sor hibás, egyik sem kerül be.
    @Transactional
    public List<LabResultResponse> createAll(LabBatchRequest request) {
        requirePatientExists(request.patientId());
        Document document = request.documentId() == null
                ? null
                : requireDocumentOfPatient(request.documentId(), request.patientId());

        List<LabResult> results = request.results().stream()
                .map(r -> new LabResult(
                        request.patientId(),
                        request.documentId(),
                        r.groupName(),
                        r.name(),
                        r.value(),
                        r.unit(),
                        r.refLow(),
                        r.refHigh(),
                        measuredAtOf(r, document),
                        r.code()))
                .toList();

        return repository.saveAll(results)
                .stream()
                .map(LabResultResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public LabResultResponse findById(Long id) {
        return repository.findById(id)
                .map(LabResultResponse::from)
                .orElseThrow(() -> new LabResultNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<LabResultResponse> findByPatient(Long patientId, String name) {
        requirePatientExists(patientId);
        List<LabResult> results = name == null
                ? repository.findByPatientIdOrderByMeasuredAtDescIdDesc(patientId)
                : repository.findByPatientIdAndNameOrderByMeasuredAtAscIdAsc(patientId, name);
        return results.stream()
                .map(LabResultResponse::from)
                .toList();
    }

    // Identitás-kapu: a DB összetett FK-ja is kizárja az eltérést, ez csak 500 helyett 409-et ad.
    private Document requireDocumentOfPatient(Long documentId, Long patientId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!document.getPatientId().equals(patientId)) {
            throw new LabDocumentPatientMismatchException(documentId, document.getPatientId(), patientId);
        }
        return document;
    }

    private static OffsetDateTime measuredAtOf(LabResultRequest r, Document document) {
        if (r.measuredAt() != null) {
            return r.measuredAt();
        }
        if (document != null && document.getClinicalDate() != null) {
            return document.getClinicalDate().atStartOfDay(CLINICAL_ZONE).toOffsetDateTime();
        }
        throw new IllegalArgumentException("A(z) " + r.name()
                + " eredményhez kötelező a measuredAt (nincs dokumentum klinikai dátummal, amiből át lehetne venni)");
    }

    private void requirePatientExists(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
    }
}
