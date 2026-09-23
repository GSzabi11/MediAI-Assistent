package bme.mediai.statement.service;

import bme.mediai.statement.api.ApprovalCreateRequest;
import bme.mediai.statement.api.ApprovalResponse;
import bme.mediai.statement.exception.ApproverNotDoctorException;
import bme.mediai.statement.exception.RuleStatementNotModifiableException;
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
