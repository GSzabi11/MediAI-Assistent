/**
 * API-kliens réteg. A komponensek CSAK ezt az `api` objektumot használják,
 * soha nem hívnak fetch-et közvetlenül. Itt képeződnek le a backend DTO-i
 * (dto.ts) a nézet-típusokra (types.ts) — a leképezés egyetlen helye.
 */
import { getCurrentUser, type CurrentUser } from './currentUser';
import type {
  ApprovalDto,
  ApprovalStatusDto,
  DocumentDto,
  LabResultDto,
  LoginDto,
  UserCreateDto,
  PatientDto,
  PatientSearchDto,
  RecordingDto,
  StatementDto,
  StatementSourceDto,
  UserDto,
  UtteranceDto,
} from './dto';
import { http } from './http';
import {
  ApiError,
  NotAvailableError,
  type ChatValasz,
  type ClaimResponse,
  type ClaimType,
  type Dontes,
  type DokumentumListItem,
  type DokumentumResponse,
  type FelvetelResponse,
  type Forras,
  type JovahagyasRequest,
  type JovahagyasResponse,
  type LaborIdosor,
  type LaborParameter,
  type MegszolalasResponse,
  type PatientResponse,
  type PatientSearch,
} from './types';

export interface Api {
  belep(req: LoginDto): Promise<CurrentUser>;
  regisztral(req: UserCreateDto): Promise<CurrentUser>;

  listPatients(): Promise<PatientResponse[]>;
  searchPatients(q: PatientSearch): Promise<PatientResponse[]>;
  getPatient(id: number): Promise<PatientResponse>;

  listAllitasok(patientId: number): Promise<ClaimResponse[]>;
  jovahagy(allitasId: number, req: JovahagyasRequest): Promise<JovahagyasResponse>;

  listDokumentumok(patientId: number): Promise<DokumentumListItem[]>;
  getDokumentum(id: number): Promise<DokumentumResponse>;

  listFelvetelek(patientId: number): Promise<FelvetelResponse[]>;
  listMegszolalasok(felvetelId: number): Promise<MegszolalasResponse[]>;

  getLaborIdosor(patientId: number): Promise<LaborIdosor>;
  /** nincs backend-végpont: NotAvailableError */
  kerdez(patientId: number, kerdes: string): Promise<ChatValasz>;
}

// ---------- felhasználónevek (a jóváhagyó megjelenítéséhez) ----------

let usersCache: Promise<Map<number, string>> | null = null;

function userNames(): Promise<Map<number, string>> {
  usersCache ??= http
    .get<UserDto[]>('/users')
    .then((users) => new Map(users.map((u) => [u.id, u.username])))
    .catch((e) => {
      usersCache = null; // következő hívásnál újrapróbálja
      throw e;
    });
  return usersCache;
}

const nevOf = (names: Map<number, string>, id: number) => names.get(id) ?? `felhasználó #${id}`;

// ---------- leképezések ----------

const toPatient = (p: PatientDto): PatientResponse => ({
  id: p.id,
  nev: p.name,
  taj: p.taj,
  szuletesiDatum: p.birthDate,
  anyjaNeve: p.motherName,
});

const claimType: Record<StatementDto['type'], ClaimType> = {
  DETERMINISTIC_RULE: 'szabaly',
  RETRIEVED_FACT: 'forras',
  MODEL_INFERENCE: 'modell',
};

const dontesOf: Record<ApprovalStatusDto, Dontes> = {
  ACCEPTED: 'jovahagyva',
  REJECTED: 'elutasitva',
  MODIFIED: 'modositva',
};

const statusOf: Record<Dontes, ApprovalStatusDto> = {
  jovahagyva: 'ACCEPTED',
  elutasitva: 'REJECTED',
  modositva: 'MODIFIED',
};

function toForras(s: StatementSourceDto): Forras {
  switch (s.type) {
    case 'AUDIO':
      return { tipus: 'megszolalas', felvetelId: s.recordingId, turnIds: s.turnIds };
    case 'DOCUMENT':
      return { tipus: 'dokumentum', dokumentumId: s.documentId, kezdet: s.charStart, veg: s.charEnd };
    case 'IMAGE':
      return { tipus: 'kep', dokumentumId: s.documentId, oldal: s.page };
  }
}

function toClaim(s: StatementDto, names: Map<number, string>): ClaimResponse {
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
    forrasok: [toForras(s.source)],
    jovahagyas:
      dontes && s.reviewedBy !== null && s.reviewedAt !== null
        ? {
            allitasId: s.id,
            dontes,
            modositottSzoveg: dontes === 'modositva' ? s.effectiveText : null,
            felhasznaloNev: nevOf(names, s.reviewedBy),
            idopont: s.reviewedAt,
          }
        : null,
  };
}

const dokTipus: Record<DocumentDto['type'], { tipus: DokumentumListItem['tipus']; cim: string }> = {
  LAB: { tipus: 'labor', cim: 'Laborlelet' },
  AMBULANCE: { tipus: 'ambulans', cim: 'Ambuláns lap' },
  IMAGING: { tipus: 'kepalkotas', cim: 'Képalkotó lelet' },
};

const toDokumentum = (d: DocumentDto): DokumentumListItem => ({
  id: d.id,
  patientId: d.patientId,
  tipus: dokTipus[d.type].tipus,
  cim: dokTipus[d.type].cim,
  osztaly: null,
  datum: d.clinicalDate ?? d.uploadedAt,
  mimeTipus: d.mimeType,
  vanSzoveg: d.hasExtractedText,
});

const toFelvetel = (r: RecordingDto): FelvetelResponse => ({
  id: r.id,
  patientId: r.patientId,
  mod: r.mode === 'SBO' ? 'surgossegi' : 'echo_diktalas',
  kezdes: r.recordedAt,
  hosszMp: r.durationSec ?? 0,
});

const beszelo: Record<UtteranceDto['speaker'], MegszolalasResponse['beszelo']> = {
  DOCTOR: 'orvos',
  PATIENT: 'beteg',
  OTHER: 'egyeb',
};

const toMegszolalas = (u: UtteranceDto): MegszolalasResponse => ({
  id: u.id,
  felvetelId: u.recordingId,
  sorszam: u.turnId,
  beszelo: beszelo[u.speaker],
  kezdetMp: Math.round(u.startMs / 1000),
  szoveg: u.text,
});

/** Egy paraméter = azonos név ÉS mértékegység: különböző egységű mérés nem kerül egy trendbe. */
function toLaborIdosor(patientId: number, results: LabResultDto[]): LaborIdosor {
  const byKey = new Map<string, LaborParameter>();
  for (const r of results) {
    const kod = `${r.name}|${r.unit ?? ''}`;
    let p = byKey.get(kod);
    if (!p) {
      p = {
        kod,
        nev: r.name,
        mertekegyseg: r.unit ?? '',
        refAlso: r.refLow,
        refFelso: r.refHigh,
        celtartomany: false,
        meresek: [],
      };
      byKey.set(kod, p);
    }
    // a legutóbbi mérés referencia-tartománya érvényes (a lelet dátuma szerint)
    if (p.meresek.every((m) => m.datum <= r.measuredAt)) {
      p.refAlso = r.refLow;
      p.refFelso = r.refHigh;
    }
    p.meresek.push({ datum: r.measuredAt, ertek: Number(r.value), dokumentumId: r.documentId });
  }
  return { patientId, parameterek: [...byKey.values()] };
}

function toSearchDto(q: PatientSearch): PatientSearchDto {
  return q.mod === 'taj'
    ? { taj: q.taj }
    : { name: q.nev.trim(), motherName: q.anyjaNeve.trim(), birthDate: q.szuletesiDatum };
}

// ---------- az api ----------

const toCurrentUser = (u: UserDto): CurrentUser => ({ id: u.id, username: u.username, role: u.role });

export const api: Api = {
  // A jelszó csak a kérés törzsében megy, sosem URL-ben
  belep: async (req) => toCurrentUser(await http.post<UserDto>('/auth/login', req)),
  regisztral: async (req) => {
    const u = await http.post<UserDto>('/users', req);
    usersCache = null; // az új felhasználó neve is feloldható legyen
    return toCurrentUser(u);
  },

  listPatients: async () => (await http.get<PatientDto[]>('/patients')).map(toPatient),
  // Érzékeny keresés: POST + törzs, sosem query string (CLAUDE.md)
  searchPatients: async (q) => (await http.post<PatientDto[]>('/patients/search', toSearchDto(q))).map(toPatient),
  getPatient: async (id) => toPatient(await http.get<PatientDto>(`/patients/${id}`)),

  listAllitasok: async (patientId) => {
    const [statements, names] = await Promise.all([
      http.get<StatementDto[]>(`/statements?patientId=${patientId}`),
      userNames(),
    ]);
    return statements.map((s) => toClaim(s, names));
  },
  jovahagy: async (allitasId, req) => {
    const user = getCurrentUser();
    if (!user) throw new ApiError(401, 'Jóváhagyáshoz be kell jelentkezni.');
    const a = await http.post<ApprovalDto>(`/statements/${allitasId}/approvals`, {
      userId: user.id,
      status: statusOf[req.dontes],
      modifiedText: req.dontes === 'modositva' ? req.modositottSzoveg : undefined,
    });
    return {
      allitasId: a.statementId,
      dontes: dontesOf[a.status],
      modositottSzoveg: a.modifiedText,
      felhasznaloNev: nevOf(await userNames(), a.userId),
      idopont: a.decidedAt,
    };
  },

  listDokumentumok: async (patientId) =>
    (await http.get<DocumentDto[]>(`/documents?patientId=${patientId}`)).map(toDokumentum),
  getDokumentum: async (id) => {
    const d = toDokumentum(await http.get<DocumentDto>(`/documents/${id}`));
    const szoveg = d.vanSzoveg ? await http.getText(`/documents/${id}/text`) : null;
    return { ...d, szoveg, fajlUrl: `${import.meta.env.VITE_API_BASE ?? '/api'}/documents/${id}/file` };
  },

  listFelvetelek: async (patientId) =>
    (await http.get<RecordingDto[]>(`/recordings?patientId=${patientId}`)).map(toFelvetel),
  listMegszolalasok: async (felvetelId) =>
    (await http.get<UtteranceDto[]>(`/recordings/${felvetelId}/utterances`)).map(toMegszolalas),

  getLaborIdosor: async (patientId) =>
    toLaborIdosor(patientId, await http.get<LabResultDto[]>(`/lab-results?patientId=${patientId}`)),

  kerdez: () => Promise.reject(new NotAvailableError('Klinikai asszisztens')),
};
