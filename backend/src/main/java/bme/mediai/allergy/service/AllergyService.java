package bme.mediai.allergy.service;

import bme.mediai.allergy.api.AllergyCreateRequest;
import bme.mediai.allergy.api.AllergyResponse;
import bme.mediai.allergy.exception.AllergyNotFoundException;
import bme.mediai.allergy.exception.DuplicateAllergyException;
import bme.mediai.allergy.persistence.Allergy;
import bme.mediai.allergy.persistence.AllergyRepository;
import bme.mediai.common.persistence.EntryOrigin;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.persistence.PatientRepository;
import bme.mediai.statement.service.StatementApprovalService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class AllergyService {

    private final AllergyRepository repository;
    private final PatientRepository patientRepository;
    private final StatementApprovalService approvalService;

    public AllergyService(AllergyRepository repository, PatientRepository patientRepository,
            StatementApprovalService approvalService) {
        this.repository = repository;
        this.patientRepository = patientRepository;
        this.approvalService = approvalService;
    }

    @Transactional
    public AllergyResponse create(AllergyCreateRequest request) {
        requirePatientExists(request.patientId());
        // Az allergialista orvosi tényként jelenik meg: kézzel csak jóváhagyott állításból
        if (request.sourceStatementId() != null) {
            approvalService.requireApprovedStatementOfPatient(request.sourceStatementId(), request.patientId());
        }
        if (repository.existsForPatient(request.patientId(), request.substance())) {
            throw new DuplicateAllergyException(request.patientId(), request.substance().strip());
        }

        Allergy allergy = new Allergy(request.patientId(), request.substance(), request.sourceStatementId(),
                EntryOrigin.MANUAL);
        return AllergyResponse.from(repository.save(allergy), true);
    }

    // Az automatikus dokumentum-feldolgozás: "nem ellenőrzött", amíg a forrás-állítást orvos
    // el nem fogadja. Ha az anyag már szerepel, nem hiba (üres Optional): a leletek ismétlik.
    @Transactional
    public Optional<AllergyResponse> createFromPipeline(Long patientId, String substance, Long sourceStatementId) {
        if (repository.existsForPatient(patientId, substance)) {
            return Optional.empty();
        }
        Allergy allergy = new Allergy(patientId, substance, sourceStatementId, EntryOrigin.DOCUMENT_PIPELINE);
        return Optional.of(AllergyResponse.from(repository.save(allergy), false));
    }

    @Transactional(readOnly = true)
    public AllergyResponse findById(Long id) {
        Allergy allergy = repository.findById(id)
                .orElseThrow(() -> new AllergyNotFoundException(id));
        return withVerification(List.of(allergy)).getFirst();
    }

    @Transactional(readOnly = true)
    public List<AllergyResponse> findByPatient(Long patientId) {
        requirePatientExists(patientId);
        return withVerification(repository.findByPatientIdOrderBySubstance(patientId));
    }

    // Kézi tétel mindig ellenőrzött; a pipeline-tétel akkor, ha a forrás-állítás legfrissebb
    // döntése ACCEPTED/MODIFIED.
    private List<AllergyResponse> withVerification(List<Allergy> allergies) {
        Set<Long> approved = approvalService.approvedAmong(allergies.stream()
                .filter(a -> a.getOrigin() == EntryOrigin.DOCUMENT_PIPELINE)
                .map(Allergy::getSourceStatementId)
                .filter(Objects::nonNull)
                .toList());
        return allergies.stream()
                .map(a -> AllergyResponse.from(a,
                        a.getOrigin() == EntryOrigin.MANUAL || approved.contains(a.getSourceStatementId())))
                .toList();
    }

    private void requirePatientExists(Long patientId) {
        if (!patientRepository.existsById(patientId)) {
            throw new PatientNotFoundException(patientId);
        }
    }
}
