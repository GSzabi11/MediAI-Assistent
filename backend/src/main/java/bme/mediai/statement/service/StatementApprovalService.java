package bme.mediai.statement.service;

import bme.mediai.statement.api.ApprovalCreateRequest;
import bme.mediai.statement.api.ApprovalResponse;
import bme.mediai.statement.exception.ApproverNotDoctorException;
import bme.mediai.statement.exception.RuleStatementNotModifiableException;
import bme.mediai.statement.exception.SourceStatementNotUsableException;
import bme.mediai.statement.exception.StatementNotFoundException;
import bme.mediai.statement.persistence.ApprovalStatus;
import bme.mediai.statement.persistence.Statement;
import bme.mediai.statement.persistence.StatementApproval;
import bme.mediai.statement.persistence.StatementApprovalRepository;
import bme.mediai.statement.persistence.StatementRepository;
import bme.mediai.statement.persistence.StatementType;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.persistence.Role;
import bme.mediai.user.persistence.User;
import bme.mediai.user.persistence.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StatementApprovalService {

    private final StatementApprovalRepository repository;
    private final StatementRepository statementRepository;
    private final UserRepository userRepository;

    public StatementApprovalService(StatementApprovalRepository repository,
            StatementRepository statementRepository, UserRepository userRepository) {
        this.repository = repository;
        this.statementRepository = statementRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ApprovalResponse create(Long statementId, ApprovalCreateRequest request) {
        Statement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new StatementNotFoundException(statementId));

        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new UserNotFoundException(request.userId()));
        if (user.getRole() != Role.DOCTOR) {
            throw new ApproverNotDoctorException(user.getId());
        }

        // Egy szabály kimenete reprodukálható tény: az orvos elutasíthatja, de nem írhatja át.
        if (statement.getType() == StatementType.DETERMINISTIC_RULE
                && request.status() == ApprovalStatus.MODIFIED) {
            throw new RuleStatementNotModifiableException(statementId);
        }

        StatementApproval approval = new StatementApproval(
                statementId, user.getId(), request.status(), request.modifiedText());
        return ApprovalResponse.from(repository.save(approval));
    }

    // Human-in-the-loop: orvosi tényként megjelenő rekord (gyógyszer, allergia) csak olyan
    // állításból származhat, amit orvos elfogadott (ACCEPTED) vagy javított (MODIFIED).
    // A beteg-egyezést a hívó tábla összetett FK-ja is kikényszeríti; ez csak érthető 409-et ad.
    @Transactional(readOnly = true)
    public void requireApprovedStatementOfPatient(Long statementId, Long patientId) {
        Statement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new StatementNotFoundException(statementId));
        if (!statement.getPatientId().equals(patientId)) {
            throw new SourceStatementNotUsableException("A(z) " + statementId + " állítás a(z) "
                    + statement.getPatientId() + " beteghez tartozik, nem a(z) " + patientId + " beteghez");
        }
        ApprovalStatus status = repository.findFirstByStatementIdOrderByDecidedAtDescIdDesc(statementId)
                .map(StatementApproval::getStatus)
                .orElse(null);
        if (status != ApprovalStatus.ACCEPTED && status != ApprovalStatus.MODIFIED) {
            throw new SourceStatementNotUsableException("A(z) " + statementId
                    + " állítás nincs orvosilag jóváhagyva (állapot: " + (status == null ? "PENDING" : status)
                    + "); csak elfogadott vagy módosított állítás lehet forrás");
        }
    }

    @Transactional(readOnly = true)
    public List<ApprovalResponse> findHistory(Long statementId) {
        if (!statementRepository.existsById(statementId)) {
            throw new StatementNotFoundException(statementId);
        }
        return repository.findByStatementIdOrderByDecidedAtDescIdDesc(statementId)
                .stream()
                .map(ApprovalResponse::from)
                .toList();
    }
}
