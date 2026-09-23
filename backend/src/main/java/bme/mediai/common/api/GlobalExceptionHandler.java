package bme.mediai.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import bme.mediai.document.exception.ChunksAlreadyExistException;
import bme.mediai.document.exception.DocumentNotFoundException;
import bme.mediai.document.exception.DuplicateDocumentException;
import bme.mediai.document.exception.ExtractedTextAlreadySetException;
import bme.mediai.document.exception.ExtractedTextMissingException;
import bme.mediai.statement.exception.ApproverNotDoctorException;
import bme.mediai.statement.exception.RuleStatementNotModifiableException;
import bme.mediai.statement.exception.StatementNotFoundException;
import bme.mediai.statement.exception.StatementSourcePatientMismatchException;
import bme.mediai.lab.exception.LabDocumentPatientMismatchException;
import bme.mediai.lab.exception.LabResultNotFoundException;
import bme.mediai.medicalcare.exception.MedicalCareClosedException;
import bme.mediai.medication.exception.MedicationAlreadyDiscontinuedException;
import bme.mediai.medication.exception.MedicationNotFoundException;
import bme.mediai.medication.exception.SourceStatementNotUsableException;
import bme.mediai.medicalcare.exception.MedicalCareNotFoundException;
import bme.mediai.medicalcare.exception.MedicalCarePatientMismatchException;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.exception.TajConflictException;
import bme.mediai.recording.exception.InvalidStatusTransitionException;
import bme.mediai.recording.exception.RecordingNotFoundException;
import bme.mediai.user.exception.EmailConflictException;
import bme.mediai.utterance.exception.RecordingNotProcessingException;
import bme.mediai.utterance.exception.UtterancesAlreadyExistException;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.exception.UsernameConflictException;
import bme.mediai.voiceprofile.exception.VoiceProfileNotFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PatientNotFoundException.class)
    ProblemDetail handleNotFound(PatientNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(TajConflictException.class)
    ProblemDetail handleConflict(TajConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    ProblemDetail handleUserNotFound(UserNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({UsernameConflictException.class, EmailConflictException.class})
    ProblemDetail handleUserConflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(VoiceProfileNotFoundException.class)
    ProblemDetail handleVoiceProfileNotFound(VoiceProfileNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(RecordingNotFoundException.class)
    ProblemDetail handleRecordingNotFound(RecordingNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidStatusTransitionException.class)
    ProblemDetail handleInvalidTransition(InvalidStatusTransitionException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler({RecordingNotProcessingException.class, UtterancesAlreadyExistException.class})
    ProblemDetail handleUtteranceConflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(MedicalCareNotFoundException.class)
    ProblemDetail handleMedicalCareNotFound(MedicalCareNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({MedicalCareClosedException.class, MedicalCarePatientMismatchException.class})
    ProblemDetail handleMedicalCareConflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(StatementNotFoundException.class)
    ProblemDetail handleStatementNotFound(StatementNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(StatementSourcePatientMismatchException.class)
    ProblemDetail handleStatementSourceMismatch(StatementSourcePatientMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler({ApproverNotDoctorException.class, RuleStatementNotModifiableException.class})
    ProblemDetail handleApprovalConflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ProblemDetail handleDocumentNotFound(DocumentNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({DuplicateDocumentException.class, ExtractedTextAlreadySetException.class,
            ExtractedTextMissingException.class, ChunksAlreadyExistException.class})
    ProblemDetail handleDocumentConflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(LabResultNotFoundException.class)
    ProblemDetail handleLabResultNotFound(LabResultNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(LabDocumentPatientMismatchException.class)
    ProblemDetail handleLabDocumentMismatch(LabDocumentPatientMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(MedicationNotFoundException.class)
    ProblemDetail handleMedicationNotFound(MedicationNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({MedicationAlreadyDiscontinuedException.class, SourceStatementNotUsableException.class})
    ProblemDetail handleMedicationConflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}