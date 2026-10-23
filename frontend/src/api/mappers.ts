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
  ClinicalSummaryDto,
  MedicalHistoryDto,
  ChatMessageDto,
  KeywordDto,
  RecordingSummaryDto,
  ArticlePageDto,
  BookmarkDto,
  ResearchArticleDto,
  ResearchContextDto,
  ResearchDto,
} from './dto';
import type {
  Allergia,
  ClaimResponse,
  ClaimType,
  Dontes,
  DokumentumListItem,
  DokumentumTipus,
  FeldolgozasAllapot,
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
  LaborSzovegesParameter,
  LaborParameter,
  LaborReszlet,
  MegszolalasResponse,
  NaploBejegyzes,
  PatientResponse,
  RagDarab,
  KlinikaiOsszefoglalo,
  Kortortenet,
  ChatValasz,
  FelvetelOsszefoglalo,
  Kulcsszo,
  Cikk,
  CikkOldal,
  Konyvjelzo,
  Kutatas,
  KutatasiOsszefoglalo,
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

const hangprofilAllapotOf = { PROCESSING: 'feldolgozas', READY: 'kesz', FAILED: 'hiba' } as const;

export const toHangprofil = (v: VoiceProfileDto): Hangprofil => ({
  id: v.id,
  felhasznaloId: v.userId,
  allapot: hangprofilAllapotOf[v.status],
  hiba: v.error,
  aktiv: v.active,
  hasznalhato: v.usableForRecording,
  embeddingModell: v.embeddingModel,
  kuszob: v.threshold === null ? null : Number(v.threshold),
  nettoBeszedMp: v.netSpeechSec === null ? null : Number(v.netSpeechSec),
  mondatok: v.sentencesUsed,
  figyelmeztetesek: v.warnings ?? [],
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
    case 'LAB':
      return { tipus: 'labor', laborId: s.labResultId };
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
    case 'labor':
      throw new Error('Laborsor-forrású állítást csak a szabálymotor hoz létre');
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

const feldolgozasOf: Record<DocumentDto['status'], FeldolgozasAllapot> = {
  PROCESSING: 'feldolgozas',
  PROCESSED: 'kesz',
  FAILED: 'hiba',
};

export const toDokumentum = (d: DocumentDto): DokumentumListItem => ({
  id: d.id,
  patientId: d.patientId,
  tipus: d.type === null ? null : dokTipus[d.type].tipus,
  cim: d.type !== null ? dokTipus[d.type].cim : d.status === 'FAILED' ? 'Feldolgozás sikertelen' : 'Feldolgozás alatt…',
  allapot: feldolgozasOf[d.status],
  feldolgozasiHiba: d.processingError,
  figyelmeztetesek: d.processingWarnings ?? [],
  kinyero: d.extraction && {
    modell: d.extraction.model,
    seed: d.extraction.seed,
    promptHash: d.extraction.promptHash,
    verzio: d.extraction.extractorVersion,
  },
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
  // a böngészőből feltöltött felvétel útja: {beteg}/hangfelvetelek/… (a régebbiek: recordings/{beteg}/…);
  // a kézi rögzítésé tetszőleges
  automatikus: /^(\d+\/hangfelvetelek\/|recordings\/)/.test(r.audioPath),
  hiba: r.processingError ?? null,
  figyelmeztetesek: r.processingWarnings ?? [],
  atnezoModell: r.reviewModel ?? null,
  hangTorolve: r.audioDeleted ?? false,
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
  hasonlosag: u.similarity,
  eredetiSzoveg: u.asrText ?? null,
  eredetiBeszelo: u.asrSpeaker ? beszelo[u.asrSpeaker] : null,
  javito: u.correctedBy ?? null,
  javitasOka: u.correctionReason ?? null,
  megjegyzes: u.reviewNote ?? null,
});

// ---------- labor ----------

/**
 * Egy paraméter = azonos név ÉS mértékegység: különböző egységű mérés nem kerül egy trendbe.
 * A név kis/nagybetű- és szóköz-független ("eGFR-EPI" = "EGFR-EPI"), a mértékegység a backend
 * egységes írásmódja ("Giga/L" = "G/L"; a kis/nagybetű ott számít: g/L ≠ G/L). A leletek
 * írásmódja eltér, a vizsgálat ugyanaz. Megjelenítésre a legutóbbi mérés írásmódja.
 */
const paramKulcs = (name: string, unit: string | null) =>
  `${name.trim().replace(/\s+/g, ' ').toLocaleLowerCase('hu')}|${(unit ?? '').trim()}`;

export function toLaborIdosor(patientId: number, results: LabResultDto[]): LaborIdosor {
  const byKey = new Map<string, LaborParameter & { refIdo: number }>();
  const szovegesek = new Map<string, LaborSzovegesParameter & { refIdo: number }>();
  for (const r of results) {
    if (r.value === null) {
      // Kvalitatív eredmény: külön lista — a grafikon, a trend és a tartomány-számítás számot vár
      const kod = `${paramKulcs(r.canonicalName, r.canonicalUnit)}|szöveg`;
      const ido = new Date(r.measuredAt).getTime();
      let p = szovegesek.get(kod);
      if (!p) {
        p = {
          kod,
          nev: r.canonicalName,
          mertekegyseg: r.canonicalUnit ?? '',
          refSzoveg: r.refText,
          meresek: [],
          refIdo: ido,
        };
        szovegesek.set(kod, p);
      }
      if (ido >= p.refIdo) {
        p.refIdo = ido;
        p.nev = r.canonicalName;
        p.refSzoveg = r.refText;
      }
      p.meresek.push({ id: r.id, datum: r.measuredAt, szoveg: r.valueText ?? '', dokumentumId: r.documentId });
      continue;
    }
    const kod = paramKulcs(r.canonicalName, r.canonicalUnit);
    const ido = new Date(r.measuredAt).getTime();
    let p = byKey.get(kod);
    if (!p) {
      p = {
        kod,
        nev: r.canonicalName,
        mertekegyseg: r.canonicalUnit ?? '',
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
      p.nev = r.canonicalName;
      p.mertekegyseg = r.canonicalUnit ?? '';
      p.refAlso = r.refLow === null ? null : Number(r.refLow);
      p.refFelso = r.refHigh === null ? null : Number(r.refHigh);
    }
    p.meresek.push({
      id: r.id,
      datum: r.measuredAt,
      ertek: Number(r.value),
      jel: r.comparator,
      dokumentumId: r.documentId,
    });
  }
  return {
    patientId,
    parameterek: [...byKey.values()].map(({ refIdo: _refIdo, ...p }) => p),
    szovegesek: [...szovegesek.values()].map(({ refIdo: _refIdo, ...p }) => p),
  };
}

export const toLaborReszlet = (r: LabResultDto): LaborReszlet => ({
  id: r.id,
  patientId: r.patientId,
  dokumentumId: r.documentId,
  csoport: r.groupName,
  nev: r.canonicalName,
  leletNev: r.name,
  ertek: r.value === null ? null : Number(r.value),
  jel: r.comparator,
  szoveg: r.valueText,
  mertekegyseg: r.unit,
  refAlso: r.refLow === null ? null : Number(r.refLow),
  refFelso: r.refHigh === null ? null : Number(r.refHigh),
  refSzoveg: r.refText,
  idopont: r.measuredAt,
  kod: r.code,
  forrasDoboz: r.sourceBox && {
    oldal: r.sourceBox.page,
    x: r.sourceBox.x,
    y: r.sourceBox.y,
    szelesseg: r.sourceBox.width,
    magassag: r.sourceBox.height,
  },
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
  automatikus: m.origin === 'DOCUMENT_PIPELINE',
  ellenorzott: m.verified,
});

export const toAllergia = (a: AllergyDto): Allergia => ({
  id: a.id,
  patientId: a.patientId,
  anyag: a.substance,
  forrasAllitasId: a.sourceStatementId,
  automatikus: a.origin === 'DOCUMENT_PIPELINE',
  ellenorzott: a.verified,
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

const osszefoglaloAllapot = { PROCESSING: 'keszul', DONE: 'kesz', FAILED: 'hiba' } as const;

export const toOsszefoglalo = (s: ClinicalSummaryDto): KlinikaiOsszefoglalo => ({
  id: s.id,
  allapot: osszefoglaloAllapot[s.status],
  kerve: s.requestedAt,
  elkeszult: s.completedAt,
  hiba: s.error,
  idoszakKezdete: s.periodStart,
  idoszakVege: s.periodEnd,
  osszefoglalo: s.summary,
  javaslatok: s.recommendations ?? [],
  figyelmeztetesek: s.warnings ?? [],
  modell: s.model && {
    nev: s.model.modelName,
    seed: s.model.seed,
    promptHash: s.model.promptHash,
    verzio: s.model.extractorVersion,
  },
  bemenet: s.inputCounts ?? {},
  elavult: s.stale,
});

const kortortenetFajta = { LAB: 'labor', DOCUMENT: 'dokumentum', HISTORY: 'elozmeny' } as const;

export const toKortortenet = (h: MedicalHistoryDto): Kortortenet => ({
  id: h.id,
  allapot: osszefoglaloAllapot[h.status],
  kerve: h.requestedAt,
  elkeszult: h.completedAt,
  hiba: h.error,
  idovonal: (h.timeline ?? []).map((t) => ({
    datum: t.date,
    cim: t.title,
    szoveg: t.text,
    hely: t.place,
    dokumentumId: t.documentId,
    fajta: kortortenetFajta[t.kind],
    jeloles: t.flag,
  })),
  diagnozisok: (h.diagnoses ?? []).map((d) => ({
    nev: d.name,
    bno: d.bnoCode,
    datum: d.date,
    dokumentumId: d.documentId,
  })),
  figyelmeztetesek: h.warnings ?? [],
  modell: h.model && {
    nev: h.model.modelName,
    seed: h.model.seed,
    promptHash: h.model.promptHash,
    verzio: h.model.extractorVersion,
  },
  bemenet: h.inputCounts ?? {},
  aiHtml: h.aiHtml,
  html: h.html,
  szerkesztve: h.editedAt,
  elavult: h.stale,
  haladas: h.progress && {
    uzenet: h.progress.message,
    lepes: h.progress.step,
    osszes: h.progress.total,
    ota: h.progress.at,
  },
});

export const toChatValasz = (m: ChatMessageDto): ChatValasz => ({
  id: m.id,
  patientId: m.patientId,
  allapot: osszefoglaloAllapot[m.status],
  kerdes: m.question,
  kerve: m.requestedAt,
  hiba: m.error,
  bizonytalansag: m.uncertainty ? (m.uncertainty.toLowerCase() as ChatValasz['bizonytalansag']) : null,
  pontok: (m.answer ?? []).map((p) => ({
    szoveg: p.text,
    lista: p.list,
    bevezeto: p.heading,
    forrasok: p.sources.map((f) => ({ forras: toForras(f.source), cim: f.label })),
  })),
  figyelmeztetesek: m.warnings ?? [],
  kapcsoltSzabalyok: (m.relatedRules ?? []).map((r) => ({
    allitasId: r.statementId,
    cim: r.text,
    sulyossag: r.severity.toLowerCase() as ChatValasz['kapcsoltSzabalyok'][number]['sulyossag'],
  })),
  modell: m.model && {
    nev: m.model.modelName,
    seed: m.model.seed,
    promptHash: m.model.promptHash,
    verzio: m.model.extractorVersion,
    embedding: m.model.embeddingModel,
  },
  kontextus: m.contextCounts ?? {},
});

// ---------- felvétel: kulcsszó, összefoglaló ----------

export const toKulcsszo = (k: KeywordDto): Kulcsszo => ({
  id: k.id,
  felvetelId: k.recordingId,
  kifejezes: k.term,
  forras: k.origin === 'MODEL' ? 'ai' : 'orvos',
  allapot: k.status === 'SUGGESTED' ? 'javaslat' : k.status === 'ACCEPTED' ? 'elfogadva' : 'elutasitva',
  modell: k.modelName,
});

export const toFelvetelOsszefoglalo = (s: RecordingSummaryDto): FelvetelOsszefoglalo => ({
  id: s.id,
  felvetelId: s.recordingId,
  allapot: s.status === 'PROCESSING' ? 'feldolgozas' : s.status === 'DONE' ? 'kesz' : 'hiba',
  kerve: s.requestedAt,
  hiba: s.error,
  szoveg: s.summary,
  pontok: (s.points ?? []).map((p) => ({ szoveg: p.text, turnIds: p.turnIds })),
  kulcsszavak: s.keywords ?? [],
  figyelmeztetesek: s.warnings ?? [],
  modell: s.modelName,
  szerkesztett: s.editedText ?? null,
  szerkesztette: s.editedBy ?? null,
  szerkesztve: s.editedAt ?? null,
});

// ---------- szakirodalmi kutatás ----------
export const toKutatasiOsszefoglalo = (c: ResearchContextDto): KutatasiOsszefoglalo => ({
  id: c.id,
  allapot: c.status === 'PROCESSING' ? 'keszul' : c.status === 'DONE' ? 'kesz' : 'hiba',
  kerve: c.requestedAt,
  hiba: c.error,
  aiSzoveg: c.summary,
  aiKifejezes: c.query,
  szoveg: c.effectiveText,
  kifejezes: c.effectiveQuery,
  szerkesztett: c.edited,
  szerkesztve: c.editedAt,
  figyelmeztetesek: c.warnings ?? [],
  modell: c.model?.modelName ?? null,
});

const ERTEKELES = { NONE: 'nincs', PENDING: 'keszul', DONE: 'kesz', FAILED: 'hiba' } as const;

export const toKutatas = (r: ResearchDto): Kutatas => ({
  id: r.id,
  allapot: r.status === 'SEARCHING' ? 'keres' : r.status === 'DONE' ? 'kesz' : 'hiba',
  allapotUzenet: r.progress,
  inditva: r.createdAt,
  hiba: r.error,
  kontextus: r.contextText,
  keres: r.extraPrompt,
  kifejezes: r.query,
  forrasok: r.sources,
  osszesTalalat: r.totalFound,
  cikkek: {
    osszes: r.articles.total,
    kivett: r.articles.excluded,
    konyvjelzo: r.articles.bookmarked,
    ertekelt: r.articles.evaluated,
    varakozo: r.articles.pending,
  },
  rangsor: {
    allapot: ERTEKELES[r.ranking.status],
    uzenet: r.ranking.progress,
    hiba: r.ranking.error,
    figyelmeztetesek: r.ranking.warnings ?? [],
    modell: r.ranking.modelName,
  },
});

const RELEVANCIA = { HIGH: 'magas', MEDIUM: 'kozepes', LOW: 'alacsony' } as const;

export const toCikk = (a: ResearchArticleDto): Cikk => ({
  id: a.id,
  kutatasId: a.researchId,
  sorszam: a.rank,
  pmid: a.pmid,
  pubmedUrl: a.pubmedUrl,
  doiUrl: a.doiUrl,
  cim: a.title,
  szerzok: a.authors,
  folyoirat: a.journal,
  ev: a.pubYear,
  absztrakt: a.abstractText,
  forrasok: a.sources,
  tipusok: a.publicationTypes,
  hivatkozas: a.citationCount,
  ertekeles: {
    allapot: ERTEKELES[a.evaluation.status],
    osszefoglalo: a.evaluation.summary,
    relevancia: a.evaluation.relevance ? RELEVANCIA[a.evaluation.relevance] : null,
    indoklas: a.evaluation.relevanceReason,
    hiba: a.evaluation.error,
    figyelmeztetesek: a.evaluation.warnings ?? [],
    modell: a.evaluation.modelName,
  },
  kivett: a.excluded,
  konyvjelzo: a.bookmarked,
  rangsor: {
    elozetes: a.ranking.screenRelevance ? RELEVANCIA[a.ranking.screenRelevance] : null,
    kiemeles: a.ranking.pick,
    kiemelesIndok: a.ranking.pickReason,
    helyezes: a.ranking.position,
  },
});

export const toCikkOldal = (p: ArticlePageDto): CikkOldal => ({
  cikkek: p.content.map(toCikk),
  oldal: p.page,
  meret: p.size,
  oldalak: p.totalPages,
  osszes: p.totalElements,
});

export const toKonyvjelzo = (b: BookmarkDto): Konyvjelzo => ({
  cikk: toCikk(b.article),
  kutatasId: b.researchId,
  kifejezes: b.researchQuery,
  inditva: b.researchCreatedAt,
});
