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
import bme.mediai.document.exception.DocumentNotProcessingException;
import bme.mediai.document.exception.DocumentNotRetryableException;
import bme.mediai.document.exception.DocumentProcessingException;
import bme.mediai.document.exception.DuplicateDocumentException;
import bme.mediai.document.exception.ExtractedTextAlreadySetException;
import bme.mediai.document.exception.ExtractedTextMissingException;
import bme.mediai.keyword.exception.KeywordConflictException;
import bme.mediai.epicrisis.exception.EpicrisisConflictException;
import bme.mediai.epicrisis.exception.EpicrisisNotFoundException;
import bme.mediai.medicalhistory.exception.MedicalHistoryConflictException;
import bme.mediai.medicalhistory.exception.MedicalHistoryNotFoundException;
import bme.mediai.recordingfinding.exception.RecordingFindingConflictException;
import bme.mediai.recordingfinding.exception.RecordingFindingNotFoundException;
import bme.mediai.recordingsummary.exception.RecordingSummaryConflictException;
import bme.mediai.recordingsummary.exception.RecordingSummaryNotFoundException;
import bme.mediai.keyword.exception.KeywordNotFoundException;
import bme.mediai.lab.exception.LabDocumentPatientMismatchException;
import bme.mediai.lab.exception.LabResultNotFoundException;
import bme.mediai.medicalcare.exception.MedicalCareClosedException;
import bme.mediai.medicalcare.exception.MedicalCareNotFoundException;
import bme.mediai.medicalcare.exception.MedicalCarePatientMismatchException;
import bme.mediai.medication.exception.MedicationAlreadyDiscontinuedException;
import bme.mediai.medication.exception.MedicationNotFoundException;
import bme.mediai.patient.exception.PatientNotFoundException;
import bme.mediai.processing.exception.InvalidCallbackTokenException;
import bme.mediai.chat.exception.ChatConflictException;
import bme.mediai.chat.exception.ChatMessageNotFoundException;
import bme.mediai.research.exception.ResearchConflictException;
import bme.mediai.research.exception.ResearchNotFoundException;
import bme.mediai.summary.exception.ClinicalSummaryNotFoundException;
import bme.mediai.summary.exception.SummaryConflictException;
import bme.mediai.patient.exception.TajConflictException;
import bme.mediai.recording.exception.InvalidStatusTransitionException;
import bme.mediai.recording.exception.RecordingConflictException;
import bme.mediai.recording.exception.RecordingNotFoundException;
import bme.mediai.statement.exception.ApproverNotDoctorException;
import bme.mediai.statement.exception.RuleStatementNotModifiableException;
import bme.mediai.statement.exception.SourceStatementNotUsableException;
import bme.mediai.statement.exception.StatementNotFoundException;
import bme.mediai.statement.exception.StatementSourcePatientMismatchException;
import bme.mediai.user.exception.EmailConflictException;
import bme.mediai.user.exception.InvalidCredentialsException;
import bme.mediai.user.exception.UserNotFoundException;
import bme.mediai.user.exception.UsernameConflictException;
import bme.mediai.utterance.exception.RecordingNotProcessingException;
import bme.mediai.utterance.exception.UtterancesAlreadyExistException;
import bme.mediai.voiceprofile.exception.VoiceProfileConflictException;
import bme.mediai.voiceprofile.exception.VoiceProfileNotFoundException;
import bme.mediai.voiceprofile.exception.VoiceProfileRequiredException;

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

    @ExceptionHandler({VoiceProfileConflictException.class, VoiceProfileRequiredException.class})
    ProblemDetail handleVoiceProfileConflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(RecordingSummaryNotFoundException.class)
    ProblemDetail handleRecordingSummaryNotFound(RecordingSummaryNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(RecordingSummaryConflictException.class)
    ProblemDetail handleRecordingSummaryConflict(RecordingSummaryConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(RecordingFindingNotFoundException.class)
    ProblemDetail handleRecordingFindingNotFound(RecordingFindingNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(RecordingFindingConflictException.class)
    ProblemDetail handleRecordingFindingConflict(RecordingFindingConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(KeywordNotFoundException.class)
    ProblemDetail handleKeywordNotFound(KeywordNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(KeywordConflictException.class)
    ProblemDetail handleKeywordConflict(KeywordConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(RecordingNotFoundException.class)
    ProblemDetail handleRecordingNotFound(RecordingNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({InvalidStatusTransitionException.class, RecordingConflictException.class})
    ProblemDetail handleInvalidTransition(RuntimeException ex) {
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
            ExtractedTextMissingException.class, ChunksAlreadyExistException.class,
            DocumentProcessingException.class, DocumentNotProcessingException.class,
            DocumentNotRetryableException.class})
    ProblemDetail handleDocumentConflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ClinicalSummaryNotFoundException.class)
    ProblemDetail handleSummaryNotFound(ClinicalSummaryNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(SummaryConflictException.class)
    ProblemDetail handleSummaryConflict(SummaryConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(MedicalHistoryNotFoundException.class)
    ProblemDetail handleMedicalHistoryNotFound(MedicalHistoryNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(MedicalHistoryConflictException.class)
    ProblemDetail handleMedicalHistoryConflict(MedicalHistoryConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(EpicrisisNotFoundException.class)
    ProblemDetail handleEpicrisisNotFound(EpicrisisNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(EpicrisisConflictException.class)
    ProblemDetail handleEpicrisisConflict(EpicrisisConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ResearchNotFoundException.class)
    ProblemDetail handleResearchNotFound(ResearchNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ResearchConflictException.class)
    ProblemDetail handleResearchConflict(ResearchConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ChatMessageNotFoundException.class)
    ProblemDetail handleChatNotFound(ChatMessageNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ChatConflictException.class)
    ProblemDetail handleChatConflict(ChatConflictException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InvalidCallbackTokenException.class)
    ProblemDetail handleInvalidCallbackToken(InvalidCallbackTokenException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
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

    @ExceptionHandler(ReadOnlyModeException.class)
    ProblemDetail handleReadOnlyMode(ReadOnlyModeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
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