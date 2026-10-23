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
export type VoiceProfileStatusDto = 'PROCESSING' | 'READY' | 'FAILED';

export interface VoiceProfileDto {
  id: number;
  userId: number;
  status: VoiceProfileStatusDto;
  error: string | null;
  active: boolean;
  usableForRecording: boolean;
  embeddingModel: string | null;
  threshold: number | null;
  netSpeechSec: number | null;
  sentencesUsed: number | null;
  warnings: string[];
  microphone: string | null;
  recordedAt: string;
  createdAt: string;
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
  | { type: 'IMAGE'; documentId: number; page: number; x: number; y: number; width: number; height: number }
  /** egy laborsor — csak a szabálymotor (referencia-tartomány) állításai hivatkoznak így */
  | { type: 'LAB'; labResultId: number };

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

export type DocumentStatusDto = 'PROCESSING' | 'PROCESSED' | 'FAILED';

export interface DocumentDto {
  id: number;
  patientId: number;
  /** PDF-nél PROCESSING alatt null: az automatikus feldolgozás sorolja be */
  type: DocumentTypeDto | null;
  status: DocumentStatusDto;
  mimeType: string;
  sizeBytes: number;
  sha256: string;
  hasExtractedText: boolean;
  clinicalDate: string | null;
  uploadedAt: string;
  processedAt: string | null;
  processingError: string | null;
  processingWarnings: string[];
  extraction: { model: string; seed: number; promptHash: string; extractorVersion: string } | null;
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
  processingError: string | null;
  processingWarnings: string[];
  processedAt: string | null;
  audioDeleted: boolean;
  reviewModel: string | null;
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
  asrText: string | null;
  asrSpeaker: SpeakerDto | null;
  correctedBy: string | null;
  correctionReason: string | null;
  reviewNote: string | null;
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
export type LabComparatorDto = '<' | '<=' | '>' | '>=';

export interface LabResultDto {
  id: number;
  patientId: number;
  documentId: number | null;
  groupName: string | null;
  /** a leleten álló név, változatlanul */
  name: string;
  /** egységes név a trendhez (backend LabTestNames: "(A)" nélkül, szinonimák összevonva) */
  canonicalName: string;
  /** számérték; kvalitatív eredménynél null (ilyenkor valueText) */
  value: number | null;
  /** cenzorált eredmény (pl. ">90"): a value a határ; null = pontos érték */
  comparator: LabComparatorDto | null;
  /** kvalitatív eredmény szó szerint ("Negatív", "kevés") */
  valueText: string | null;
  unit: string | null;
  /** egységes mértékegység-írásmód ("Giga/L" → "G/L"), 1:1, átváltás nélkül */
  canonicalUnit: string | null;
  refLow: number | null;
  refHigh: number | null;
  /** szöveges referencia ("Negatív") */
  refText: string | null;
  measuredAt: string;
  code: string | null;
  /** az eredmény sora a lelet PDF-jében (normált 0–1); null, ha nem ismert */
  sourceBox: { page: number; x: number; y: number; width: number; height: number } | null;
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
/** DOCUMENT_PIPELINE: a dokumentum-feldolgozás írta be; verified = a forrás-állítást orvos elfogadta */
export type EntryOriginDto = 'MANUAL' | 'DOCUMENT_PIPELINE';

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
  origin: EntryOriginDto;
  verified: boolean;
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
  origin: EntryOriginDto;
  verified: boolean;
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

/** GET /clinical-summaries?patientId= — AI-összefoglaló (külön tábla, nem állítás) */
export interface ClinicalSummaryDto {
  id: number;
  patientId: number;
  status: 'PROCESSING' | 'DONE' | 'FAILED';
  requestedBy: number | null;
  requestedAt: string;
  completedAt: string | null;
  error: string | null;
  periodStart: string;
  periodEnd: string;
  summary: string | null;
  recommendations: string[];
  /** a szövegben a beteg adatai között nem szereplő szám */
  warnings: string[];
  model: { modelName: string; seed: number; promptHash: string; extractorVersion: string } | null;
  inputCounts: Record<string, number>;
  /** az összefoglaló óta új dokumentum/állítás jött */
  stale: boolean;
}

/** GET/POST /chat-messages — klinikai asszisztens (nem állítás, nem tárolódik) */
export interface ChatMessageDto {
  id: number;
  patientId: number;
  status: 'PROCESSING' | 'DONE' | 'FAILED';
  requestedBy: number | null;
  requestedAt: string;
  completedAt: string | null;
  error: string | null;
  question: string;
  history: number[];
  answer: {
    text: string;
    list: boolean;
    /** bevezető/címsor — nem kell forrás */
    heading: boolean;
    sources: { kind: string; id: number; label: string; source: StatementSourceDto }[];
  }[];
  /** forrás-lefedettségből számolva (nem a modell önértékelése) */
  uncertainty: UncertaintyDto | null;
  warnings: string[];
  relatedRules: { statementId: number; text: string; severity: SeverityDto }[];
  model: {
    modelName: string;
    seed: number;
    promptHash: string;
    extractorVersion: string;
    embeddingModel: string | null;
  } | null;
  contextCounts: Record<string, number>;
}

export interface KeywordDto {
  id: number;
  recordingId: number;
  term: string;
  origin: 'MODEL' | 'DOCTOR';
  status: 'SUGGESTED' | 'ACCEPTED' | 'REJECTED';
  modelName: string | null;
  userId: number | null;
  createdAt: string;
  decidedAt: string | null;
}

export interface RecordingSummaryDto {
  id: number;
  recordingId: number;
  status: 'PROCESSING' | 'DONE' | 'FAILED';
  requestedBy: number | null;
  requestedAt: string;
  completedAt: string | null;
  error: string | null;
  summary: string | null;
  points: { text: string; turnIds: number[] }[];
  keywords: string[];
  warnings: string[];
  modelName: string | null;
  editedText: string | null;
  editedBy: number | null;
  editedAt: string | null;
}

// ---------- szakirodalmi kutatás (docs/literature-research.md) ----------

/** GET/POST /research-contexts — betegállapot-összefoglaló a kutatáshoz (AI javaslat, orvos szerkeszti) */
export interface ResearchContextDto {
  id: number;
  patientId: number;
  status: 'PROCESSING' | 'DONE' | 'FAILED';
  requestedBy: number | null;
  requestedAt: string;
  completedAt: string | null;
  error: string | null;
  summary: string | null;
  query: string | null;
  effectiveText: string | null;
  effectiveQuery: string | null;
  edited: boolean;
  editedBy: number | null;
  editedAt: string | null;
  warnings: string[];
  model: { modelName: string; seed: number; promptHash: string; extractorVersion: string } | null;
}

export type ResearchSourceDto = 'PUBMED' | 'COCHRANE' | 'EUROPE_PMC';

/** GET/POST /research — egy keresés */
export interface ResearchDto {
  id: number;
  patientId: number;
  contextId: number | null;
  requestedBy: number | null;
  createdAt: string;
  status: 'SEARCHING' | 'DONE' | 'FAILED';
  progress: string | null;
  completedAt: string | null;
  error: string | null;
  contextText: string;
  extraPrompt: string | null;
  query: string;
  sources: ResearchSourceDto[];
  maxResults: number;
  totalFound: number | null;
  articles: { total: number; excluded: number; bookmarked: number; evaluated: number; pending: number };
}

export interface ResearchArticleDto {
  id: number;
  researchId: number;
  rank: number;
  pmid: string;
  doi: string | null;
  pubmedUrl: string;
  doiUrl: string | null;
  title: string;
  authors: string[];
  journal: string | null;
  pubYear: number | null;
  abstractText: string | null;
  sources: ResearchSourceDto[];
  publicationTypes: string[];
  citationCount: number | null;
  evaluation: {
    status: 'NONE' | 'PENDING' | 'DONE' | 'FAILED';
    summary: string | null;
    relevance: 'HIGH' | 'MEDIUM' | 'LOW' | null;
    relevanceReason: string | null;
    error: string | null;
    warnings: string[];
    modelName: string | null;
    seed: number | null;
    promptHash: string | null;
    evaluatedAt: string | null;
  };
  excluded: boolean;
  bookmarked: boolean;
}

export interface ArticlePageDto {
  content: ResearchArticleDto[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface BookmarkDto {
  article: ResearchArticleDto;
  researchId: number;
  researchQuery: string;
  researchCreatedAt: string;
}
