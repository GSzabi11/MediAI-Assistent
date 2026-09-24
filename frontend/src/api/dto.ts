/**
 * A Spring Boot backend válasz- és kérés-DTO-inak PONTOS tükre (backend/…/api/*Response.java).
 * Ezeket csak az api-réteg (index.ts) látja: a komponensek a types.ts nézet-típusait
 * kapják, a leképezés egy helyen van. Ha a backend DTO változik, itt és a
 * leképezésben kell igazítani, a komponensekben nem.
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

export interface PatientSearchDto {
  taj?: string;
  name?: string;
  motherName?: string;
  birthDate?: string;
}

// ---------- user ----------
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
  | { type: 'MODEL'; modelName: string; seed: number; promptHash: string; asrModel: string | null }
  | { type: 'EXTRACTOR'; extractorName: string; extractorVersion: string; asrModel: string | null };

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

// ---------- document ----------
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

// ---------- recording + utterance ----------
export interface RecordingDto {
  id: number;
  patientId: number;
  userId: number;
  medicalCareId: number | null;
  voiceProfileId: number | null;
  mode: 'SBO' | 'ECHO';
  status: 'RECORDING' | 'PROCESSING' | 'DONE' | 'FAILED';
  audioPath: string;
  durationSec: number | null;
  asrModel: string;
  recordedAt: string;
}

export interface UtteranceDto {
  id: number;
  recordingId: number;
  turnId: number;
  speaker: 'DOCTOR' | 'PATIENT' | 'OTHER';
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

// ---------- hiba (Spring ProblemDetail) ----------
export interface ProblemDetailDto {
  status?: number;
  title?: string;
  detail?: string;
}
