/**
 * DTO ↔ nézet-típus leképezések — az egyetlen hely, ahol a backend mezőnevei és
 * a komponensek nézet-típusai találkoznak.
 */
import type {
  AccessLogDto,
  AllergyDto,
  ApprovalDto,
  ApprovalStatusDto,
  ChunkDto,
  DocumentDto,
  DocumentTypeDto,
  LabResultDto,
  MedicalCareDto,
  MedicalCareTypeDto,
  MedicationDto,
  PatientDto,
  RecordingDto,
  RecordingStatusDto,
  StatementDto,
  StatementProducerDto,
  StatementSourceDto,
  UserDto,
  UtteranceDto,
  VoiceProfileDto,
} from './dto';
import type {
  Allergia,
  ClaimResponse,
  ClaimType,
  Dontes,
  DokumentumListItem,
  DokumentumTipus,
  Ellatas,
  EllatasTipus,
  Felhasznalo,
  FelvetelAllapot,
  FelvetelResponse,
  Forras,
  Gyogyszer,
  Hangprofil,
  JovahagyasElozmeny,
  LaborIdosor,
  LaborParameter,
  LaborReszlet,
  MegszolalasResponse,
  NaploBejegyzes,
  PatientResponse,
  RagDarab,
} from './types';

// ---------- beteg + felhasználó ----------

export const toPatient = (p: PatientDto): PatientResponse => ({
  id: p.id,
  nev: p.name,
  taj: p.taj,
  szuletesiDatum: p.birthDate,
  anyjaNeve: p.motherName,
  lakcim: p.address,
});

export const toFelhasznalo = (u: UserDto): Felhasznalo => ({
  id: u.id,
  felhasznalonev: u.username,
  email: u.email,
  szerepkor: u.role,
  letrehozva: u.createdAt,
});

export const toHangprofil = (v: VoiceProfileDto): Hangprofil => ({
  id: v.id,
  felhasznaloId: v.userId,
  wavUt: v.wavPath,
  embeddingModell: v.embeddingModel,
  kuszob: v.threshold === null ? null : Number(v.threshold),
  aktiv: v.active,
  nettoBeszedMp: v.netSpeechSec === null ? null : Number(v.netSpeechSec),
  mikrofon: v.microphone,
  rogzitve: v.recordedAt,
  letrehozva: v.createdAt,
});

// ---------- ellátás ----------

export const ellatasTipusOf: Record<MedicalCareTypeDto, EllatasTipus> = {
  SBO: 'sbo',
  AMBULANCE: 'ambulans',
  ECHO: 'echo',
};

export const ellatasTipusDto: Record<EllatasTipus, MedicalCareTypeDto> = {
  sbo: 'SBO',
  ambulans: 'AMBULANCE',
  echo: 'ECHO',
};

export const toEllatas = (m: MedicalCareDto): Ellatas => ({
  id: m.id,
  patientId: m.patientId,
  orvosId: m.userId,
  tipus: ellatasTipusOf[m.type],
  kezdet: m.startedAt,
  veg: m.endedAt,
});

// ---------- állítás + jóváhagyás ----------

const claimType: Record<StatementDto['type'], ClaimType> = {
  DETERMINISTIC_RULE: 'szabaly',
  RETRIEVED_FACT: 'forras',
  MODEL_INFERENCE: 'modell',
};

export const dontesOf: Record<ApprovalStatusDto, Dontes> = {
  ACCEPTED: 'jovahagyva',
  REJECTED: 'elutasitva',
  MODIFIED: 'modositva',
};

export const statusOf: Record<Dontes, ApprovalStatusDto> = {
  jovahagyva: 'ACCEPTED',
  elutasitva: 'REJECTED',
  modositva: 'MODIFIED',
};

export function toForras(s: StatementSourceDto): Forras {
  switch (s.type) {
    case 'AUDIO':
      return { tipus: 'megszolalas', felvetelId: s.recordingId, turnIds: s.turnIds };
    case 'DOCUMENT':
      return { tipus: 'dokumentum', dokumentumId: s.documentId, kezdet: s.charStart, veg: s.charEnd };
    case 'IMAGE':
      return { tipus: 'kep', dokumentumId: s.documentId, oldal: s.page };
  }
}

export function toSourceDto(f: Forras): StatementSourceDto {
  switch (f.tipus) {
    case 'megszolalas':
      return { type: 'AUDIO', recordingId: f.felvetelId, turnIds: f.turnIds };
    case 'dokumentum':
      return { type: 'DOCUMENT', documentId: f.dokumentumId, charStart: f.kezdet, charEnd: f.veg };
    case 'kep':
      throw new Error('Képforrású idézet kézzel nem rögzíthető (nincs kijelölt terület)');
  }
}

function eloallitoLeiras(p: StatementProducerDto): string {
  const asr = 'asrModel' in p && p.asrModel ? ` · ASR: ${p.asrModel}` : '';
  switch (p.type) {
    case 'RULE':
      return `Szabály: ${p.ruleId} v${p.ruleVersion}`;
    case 'MODEL':
      return `Modell: ${p.modelName} (seed ${p.seed})${asr}`;
    case 'EXTRACTOR':
      return `Kinyerő: ${p.extractorName} ${p.extractorVersion}${asr}`;
  }
}

export function toClaim(s: StatementDto, reviewerNev: string | null): ClaimResponse {
  const type = claimType[s.type];
  const dontes = s.reviewStatus === 'PENDING' ? null : dontesOf[s.reviewStatus];
  return {
    id: s.id,
    patientId: s.patientId,
    claimType: type,
    sulyossag: s.severity.toLowerCase() as ClaimResponse['sulyossag'],
    bizonytalansag: s.uncertainty ? (s.uncertainty.toLowerCase() as ClaimResponse['bizonytalansag']) : null,
    // forras: a címke a cím, a szöveg maga a szó szerinti idézet
    cim: type === 'forras' ? (s.label ?? 'Szó szerinti idézet') : s.text,
    szoveg: type !== 'forras' && s.label ? `Címke: ${s.label}` : null,
    idezet: type === 'forras' ? s.text : null,
    szabalyEredet: s.producer.type === 'RULE' ? `${s.producer.ruleId} v${s.producer.ruleVersion}` : null,
    eloallito: eloallitoLeiras(s.producer),
    ervenyesSzoveg: s.effectiveText,
    klinikaiIdo: s.clinicalTime,
    forrasok: [toForras(s.source)],
    jovahagyas:
      dontes && s.reviewedBy !== null && s.reviewedAt !== null
        ? {
            allitasId: s.id,
            dontes,
            modositottSzoveg: dontes === 'modositva' ? s.effectiveText : null,
            felhasznaloNev: reviewerNev ?? `felhasználó #${s.reviewedBy}`,
            idopont: s.reviewedAt,
          }
        : null,
  };
}

export const toJovahagyas = (a: ApprovalDto, nev: string): JovahagyasElozmeny => ({
  id: a.id,
  allitasId: a.statementId,
  dontes: dontesOf[a.status],
  modositottSzoveg: a.modifiedText,
  felhasznaloNev: nev,
  idopont: a.decidedAt,
});

// ---------- dokumentum ----------

const dokTipus: Record<DocumentTypeDto, { tipus: DokumentumTipus; cim: string }> = {
  LAB: { tipus: 'labor', cim: 'Laborlelet' },
  AMBULANCE: { tipus: 'ambulans', cim: 'Ambuláns lap' },
  IMAGING: { tipus: 'kepalkotas', cim: 'Képalkotó lelet' },
};

export const dokTipusDto: Record<DokumentumTipus, DocumentTypeDto> = {
  labor: 'LAB',
  ambulans: 'AMBULANCE',
  kepalkotas: 'IMAGING',
};

export const toDokumentum = (d: DocumentDto): DokumentumListItem => ({
  id: d.id,
  patientId: d.patientId,
  tipus: dokTipus[d.type].tipus,
  cim: dokTipus[d.type].cim,
  osztaly: null,
  datum: d.clinicalDate ?? d.uploadedAt,
  klinikaiDatum: d.clinicalDate,
  mimeTipus: d.mimeType,
  meretBajt: d.sizeBytes,
  vanSzoveg: d.hasExtractedText,
});

export const toRagDarab = (c: ChunkDto): RagDarab => ({
  id: c.id,
  dokumentumId: c.documentId,
  szoveg: c.text,
  kezdet: c.charStart,
  veg: c.charEnd,
  embeddingModell: c.embeddingModel,
});

// ---------- felvétel + megszólalás ----------

export const allapotOf: Record<RecordingStatusDto, FelvetelAllapot> = {
  RECORDING: 'rogzites',
  PROCESSING: 'feldolgozas',
  DONE: 'kesz',
  FAILED: 'hiba',
};

export const allapotDto: Record<FelvetelAllapot, RecordingStatusDto> = {
  rogzites: 'RECORDING',
  feldolgozas: 'PROCESSING',
  kesz: 'DONE',
  hiba: 'FAILED',
};

export const toFelvetel = (r: RecordingDto): FelvetelResponse => ({
  id: r.id,
  patientId: r.patientId,
  orvosId: r.userId,
  ellatasId: r.medicalCareId,
  mod: r.mode === 'SBO' ? 'surgossegi' : 'echo_diktalas',
  allapot: allapotOf[r.status],
  hangfajl: r.audioPath,
  asrModell: r.asrModel,
  kezdes: r.recordedAt,
  hosszMp: r.durationSec ?? 0,
});

const beszelo: Record<UtteranceDto['speaker'], MegszolalasResponse['beszelo']> = {
  DOCTOR: 'orvos',
  PATIENT: 'beteg',
  OTHER: 'egyeb',
};

export const toMegszolalas = (u: UtteranceDto): MegszolalasResponse => ({
  id: u.id,
  felvetelId: u.recordingId,
  sorszam: u.turnId,
  beszelo: beszelo[u.speaker],
  kezdetMp: Math.round(u.startMs / 1000),
  szoveg: u.text,
});

// ---------- labor ----------

/** Egy paraméter = azonos név ÉS mértékegység: különböző egységű mérés nem kerül egy trendbe. */
export function toLaborIdosor(patientId: number, results: LabResultDto[]): LaborIdosor {
  const byKey = new Map<string, LaborParameter & { refIdo: number }>();
  for (const r of results) {
    const kod = `${r.name}|${r.unit ?? ''}`;
    const ido = new Date(r.measuredAt).getTime();
    let p = byKey.get(kod);
    if (!p) {
      p = {
        kod,
        nev: r.name,
        mertekegyseg: r.unit ?? '',
        refAlso: r.refLow === null ? null : Number(r.refLow),
        refFelso: r.refHigh === null ? null : Number(r.refHigh),
        celtartomany: false,
        meresek: [],
        refIdo: ido,
      };
      byKey.set(kod, p);
    }
    // A legutóbbi mérés referencia-tartománya érvényes (időpont szerint, nem szövegként összevetve)
    if (ido >= p.refIdo) {
      p.refIdo = ido;
      p.refAlso = r.refLow === null ? null : Number(r.refLow);
      p.refFelso = r.refHigh === null ? null : Number(r.refHigh);
    }
    p.meresek.push({ id: r.id, datum: r.measuredAt, ertek: Number(r.value), dokumentumId: r.documentId });
  }
  return { patientId, parameterek: [...byKey.values()].map(({ refIdo: _refIdo, ...p }) => p) };
}

export const toLaborReszlet = (r: LabResultDto): LaborReszlet => ({
  id: r.id,
  patientId: r.patientId,
  dokumentumId: r.documentId,
  csoport: r.groupName,
  nev: r.name,
  ertek: Number(r.value),
  mertekegyseg: r.unit,
  refAlso: r.refLow === null ? null : Number(r.refLow),
  refFelso: r.refHigh === null ? null : Number(r.refHigh),
  idopont: r.measuredAt,
  kod: r.code,
});

// ---------- gyógyszer + allergia + napló ----------

export const toGyogyszer = (m: MedicationDto): Gyogyszer => ({
  id: m.id,
  patientId: m.patientId,
  nev: m.name,
  hatoanyag: m.activeIngredient,
  dozis: m.dose === null ? null : Number(m.dose),
  dozisEgyseg: m.doseUnit,
  alkalmazas: m.route,
  forrasAllitasId: m.sourceStatementId,
  aktiv: m.active,
});

export const toAllergia = (a: AllergyDto): Allergia => ({
  id: a.id,
  patientId: a.patientId,
  anyag: a.substance,
  forrasAllitasId: a.sourceStatementId,
});

export const toNaplo = (l: AccessLogDto): NaploBejegyzes => ({
  id: l.id,
  felhasznaloId: l.userId,
  patientId: l.patientId,
  muvelet: l.action,
  eroforras: l.resource,
  httpStatus: l.httpStatus,
  idopont: l.accessedAt,
  ip: l.ip,
});
