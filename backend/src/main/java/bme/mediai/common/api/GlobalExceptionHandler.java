package bme.mediai.common.api;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import bme.mediai.allergy.exception.AllergyNotFoundException;
import bme.mediai.allergy.exception.DuplicateAllergyException;
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
import bme.mediai.statement.exception.SourceStatementNotUsableException;
import bme.mediai.medicalcare.exception.MedicalCareNotFoundException;
import bme.mediai.medicalcare.exception.MedicalCarePatientMismatchException;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.patient.exception.TajConflictException;
import bme.mediai.recording.exception.InvalidStatusTransitionException;
import bme.mediai.recording.exception.RecordingNotFoundException;
import bme.mediai.user.exception.EmailConflictException;
import bme.mediai.user.exception.InvalidCredentialsException;
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

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
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

    @ExceptionHandler(AllergyNotFoundException.class)
    ProblemDetail handleAllergyNotFound(AllergyNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(DuplicateAllergyException.class)
    ProblemDetail handleDuplicateAllergy(DuplicateAllergyException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // --- A Spring saját hibái is ProblemDetail-t adjanak, olvasható `detail`-lel: különben a
    // --- kliens csak annyit lát, hogy „400”, és nem tudja megmondani, melyik mező a hibás.

    // @Valid bukás: mezőnként a validációs üzenet (pl. "results[1].value: ...")
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                detail.isEmpty() ? "Érvénytelen kérés" : detail);
    }

    // Nem értelmezhető törzs: hibás JSON, rossz típus (pl. "<0,5" szám helyett), ismeretlen enum-érték
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "A kérés törzse nem értelmezhető (hibás JSON, rossz mezőtípus vagy ismeretlen érték)");
    }

    // Rossz URL-paraméter-típus, pl. ?patientId=abc vagy ?role=NURSE
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Érvénytelen paraméterérték: " + ex.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail handleMissingParam(MissingServletRequestParameterException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Hiányzó paraméter: " + ex.getParameterName());
    }

    // Multipart feltöltés fájl nélkül
    @ExceptionHandler(MissingServletRequestPartException.class)
    ProblemDetail handleMissingPart(MissingServletRequestPartException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Hiányzó rész a feltöltésből: " + ex.getRequestPartName());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail handleTooLarge(MaxUploadSizeExceededException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
                "A fájl túl nagy (legfeljebb 20 MB)");
    }
}