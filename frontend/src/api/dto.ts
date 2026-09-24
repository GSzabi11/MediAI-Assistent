/**
 * A Spring Boot backend válasz- és kérés-DTO-inak PONTOS tükre (backend/…/api/*.java).
 * Ezeket csak az api-réteg (index.ts, mappers.ts) látja: a komponensek a types.ts
 * nézet-típusait kapják. Ha a backend DTO változik, itt és a leképezésben kell
 * igazítani, a komponensekben nem.
 */

// ---------- patient ----------
export interface PatientDto {
  id: number;
  name: string;
  taj: string | null;
  motherName: string | null;
  birthDate: string; // YYYY-MM-DD
  address: string | null;
  letrehozva: string;
}

/** backend PatientCreateRequest: name, motherName, birthDate kötelező; taj opcionális (9 számjegy) */
export interface PatientCreateDto {
  name: string;
  taj: string | null;
  motherName: string;
  birthDate: string;
  address: string | null;
}

export interface PatientSearchDto {
  taj?: string;
  name?: string;
  motherName?: string;
  birthDate?: string;
}

// ---------- user + auth ----------
export type RoleDto = 'DOCTOR' | 'ADMIN';

export interface UserDto {
  id: number;
  username: string;
  email: string;
  role: RoleDto;
  createdAt: string;
}

export interface UserCreateDto {
  username: string;
  email: string;
  password: string;
  role: RoleDto;
}

export interface LoginDto {
  username: string;
  password: string;
}

/** POST /users/search — pontosan az egyik (a felhasználónév az elsődleges) */
export interface UserSearchDto {
  username?: string;
  email?: string;
}

// ---------- voice profile ----------
export interface VoiceProfileDto {
  id: number;
  userId: number;
  wavPath: string;
  embeddingModel: string;
  threshold: number | null;
  active: boolean;
  netSpeechSec: number | null;
  microphone: string | null;
  recordedAt: string;
  createdAt: string;
}

export interface VoiceProfileCreateDto {
  userId: number;
  wavPath: string;
  embeddingModel: string;
  threshold: number | null;
  netSpeechSec: number | null;
  microphone: string | null;
  recordedAt: string;
}

// ---------- medical care ----------
export type MedicalCareTypeDto = 'SBO' | 'AMBULANCE' | 'ECHO';

export interface MedicalCareDto {
  id: number;
  patientId: number;
  userId: number;
  type: MedicalCareTypeDto;
  startedAt: string;
  endedAt: string | null;
}

export interface MedicalCareCreateDto {
  patientId: number;
  userId: number;
  type: MedicalCareTypeDto;
}

// ---------- statement + approval ----------
export type StatementTypeDto = 'DETERMINISTIC_RULE' | 'RETRIEVED_FACT' | 'MODEL_INFERENCE';
export type SeverityDto = 'INFO' | 'WARNING' | 'CRITICAL';
export type UncertaintyDto = 'LOW' | 'MEDIUM' | 'HIGH';
export type ReviewStatusDto = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'MODIFIED';
export type ApprovalStatusDto = 'ACCEPTED' | 'REJECTED' | 'MODIFIED';

export type StatementSourceDto =
  | { type: 'AUDIO'; recordingId: number; turnIds: number[] }
  | { type: 'DOCUMENT'; documentId: number; charStart: number; charEnd: number }
  | { type: 'IMAGE'; documentId: number; page: number; x: number; y: number; width: number; height: number };

export type StatementProducerDto =
  | { type: 'RULE'; ruleId: string; ruleVersion: string }
  | { type: 'MODEL'; modelName: string; seed: number; promptHash: string; asrModel?: string | null }
  | { type: 'EXTRACTOR'; extractorName: string; extractorVersion: string; asrModel?: string | null };

export interface StatementDto {
  id: number;
  patientId: number;
  type: StatementTypeDto;
  text: string;
  label: string | null;
  severity: SeverityDto;
  uncertainty: UncertaintyDto | null;
  source: StatementSourceDto;
  producer: StatementProducerDto;
  clinicalTime: string;
  createdAt: string;
  reviewStatus: ReviewStatusDto;
  effectiveText: string;
  reviewedBy: number | null;
  reviewedAt: string | null;
}

/** POST /statements — az API csak RETRIEVED_FACT és MODEL_INFERENCE típust fogad el */
export interface StatementCreateDto {
  patientId: number;
  type: StatementTypeDto;
  text: string;
  label: string | null;
  severity: SeverityDto;
  uncertainty: UncertaintyDto | null;
  source: StatementSourceDto;
  producer: StatementProducerDto;
  clinicalTime: string | null;
}

export interface ApprovalCreateDto {
  userId: number;
  status: ApprovalStatusDto;
  modifiedText?: string;
}

export interface ApprovalDto {
  id: number;
  statementId: number;
  userId: number;
  status: ApprovalStatusDto;
  modifiedText: string | null;
  decidedAt: string;
}

// ---------- document + chunk ----------
export type DocumentTypeDto = 'LAB' | 'AMBULANCE' | 'IMAGING';

export interface DocumentDto {
  id: number;
  patientId: number;
  type: DocumentTypeDto;
  mimeType: string;
  sizeBytes: number;
  sha256: string;
  hasExtractedText: boolean;
  clinicalDate: string | null;
  uploadedAt: string;
}

export interface ChunkDto {
  id: number;
  documentId: number;
  text: string;
  charStart: number;
  charEnd: number;
  embeddingModel: string;
}

export interface ChunkBatchDto {
  embeddingModel: string;
  chunks: { charStart: number; charEnd: number; embedding: number[] }[];
}

// ---------- recording + utterance ----------
export type RecordingStatusDto = 'RECORDING' | 'PROCESSING' | 'DONE' | 'FAILED';

export interface RecordingDto {
  id: number;
  patientId: number;
  userId: number;
  medicalCareId: number | null;
  voiceProfileId: number | null;
  mode: 'SBO' | 'ECHO';
  status: RecordingStatusDto;
  audioPath: string;
  durationSec: number | null;
  asrModel: string;
  recordedAt: string;
}

export interface RecordingCreateDto {
  patientId: number;
  userId: number;
  medicalCareId: number | null;
  mode: 'SBO' | 'ECHO';
  audioPath: string;
  asrModel: string;
}

export interface RecordingStatusChangeDto {
  status: RecordingStatusDto;
  durationSec?: number;
}

export type SpeakerDto = 'DOCTOR' | 'PATIENT' | 'OTHER';

export interface UtteranceDto {
  id: number;
  recordingId: number;
  turnId: number;
  speaker: SpeakerDto;
  similarity: number | null;
  startMs: number;
  endMs: number;
  text: string;
  asrConfidence: number | null;
}

export interface UtteranceCreateDto {
  turnId: number;
  speaker: SpeakerDto;
  similarity: number | null;
  startMs: number;
  endMs: number;
  text: string;
  asrConfidence: number | null;
}

// ---------- lab ----------
export interface LabResultDto {
  id: number;
  patientId: number;
  documentId: number | null;
  groupName: string | null;
  name: string;
  value: number;
  unit: string | null;
  refLow: number | null;
  refHigh: number | null;
  measuredAt: string;
  code: string | null;
}

export interface LabBatchDto {
  patientId: number;
  documentId: number | null;
  results: {
    groupName: string | null;
    name: string;
    value: number;
    unit: string | null;
    refLow: number | null;
    refHigh: number | null;
    measuredAt: string | null;
    code: string | null;
  }[];
}

// ---------- medication + allergy ----------
export interface MedicationDto {
  id: number;
  patientId: number;
  name: string;
  activeIngredient: string | null;
  dose: number | null;
  doseUnit: string | null;
  route: string | null;
  sourceStatementId: number | null;
  active: boolean;
}

export interface MedicationCreateDto {
  patientId: number;
  name: string;
  activeIngredient: string | null;
  dose: number | null;
  doseUnit: string | null;
  route: string | null;
  sourceStatementId: number | null;
}

export interface AllergyDto {
  id: number;
  patientId: number;
  substance: string;
  sourceStatementId: number | null;
}

export interface AllergyCreateDto {
  patientId: number;
  substance: string;
  sourceStatementId: number | null;
}

// ---------- access log ----------
export interface AccessLogDto {
  id: number;
  userId: number | null;
  patientId: number | null;
  action: 'READ' | 'WRITE' | 'EXPORT';
  resource: string;
  httpStatus: number;
  accessedAt: string;
  ip: string | null;
}

// ---------- hiba (Spring ProblemDetail) ----------
export interface ProblemDetailDto {
  status?: number;
  title?: string;
  detail?: string;
}
