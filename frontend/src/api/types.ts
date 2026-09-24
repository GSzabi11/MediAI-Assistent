/**
 * Nézet-típusok — ezt látják a komponensek. A backend DTO-k (dto.ts) ezekre
 * képeződnek le az api-rétegben (index.ts); a komponensek a backend
 * mezőneveit nem ismerik.
 */

// ---------- patient ----------
export interface PatientResponse {
  id: number;
  nev: string;
  /** 9 számjegy, formázás nélkül; null: azonosítatlan / külföldi beteg (SBO) */
  taj: string | null;
  szuletesiDatum: string; // ISO: YYYY-MM-DD
  anyjaNeve: string | null;
}

export type PatientSearch =
  { mod: 'taj'; taj: string } | { mod: 'termeszetes'; nev: string; anyjaNeve: string; szuletesiDatum: string };

// ---------- allitas + allitas_jovahagyas ----------
export type ClaimType = 'szabaly' | 'forras' | 'modell';
export type Sulyossag = 'info' | 'warning' | 'critical';
export type Bizonytalansag = 'low' | 'medium' | 'high';

/**
 * Forrás-hivatkozás.
 *  - dokumentum: [kezdet, veg) karaktertartomány, Unicode code pointban (mint a backendben)
 *  - megszolalas: a felvétel turn-sorszámai (NEM megszólalás-azonosítók)
 *  - kep: dokumentum-oldal normált (0–1) bbox-szal
 */
export type Forras =
  | { tipus: 'dokumentum'; dokumentumId: number; kezdet: number; veg: number }
  | { tipus: 'megszolalas'; felvetelId: number; turnIds: number[] }
  | { tipus: 'kep'; dokumentumId: number; oldal: number };

export type Dontes = 'jovahagyva' | 'elutasitva' | 'modositva';

export interface JovahagyasResponse {
  allitasId: number;
  dontes: Dontes;
  modositottSzoveg: string | null;
  felhasznaloNev: string;
  idopont: string; // ISO datetime
}

export interface JovahagyasRequest {
  dontes: Dontes;
  modositottSzoveg?: string;
}

export interface ClaimResponse {
  id: number;
  patientId: number;
  claimType: ClaimType;
  sulyossag: Sulyossag;
  /** csak `modell` típusnál kötelező; `szabaly`-nál a backend null-t kényszerít */
  bizonytalansag: Bizonytalansag | null;
  cim: string;
  szoveg: string | null;
  /** `forras` típusnál a szó szerinti idézet */
  idezet: string | null;
  /** `szabaly` típusnál a szabály azonosítója és verziója */
  szabalyEredet: string | null;
  forrasok: Forras[];
  jovahagyas: JovahagyasResponse | null;
}

// ---------- dokumentum ----------
export type DokumentumTipus = 'labor' | 'ambulans' | 'kepalkotas';

export interface DokumentumListItem {
  id: number;
  patientId: number;
  tipus: DokumentumTipus;
  cim: string;
  osztaly: string | null;
  datum: string;
  mimeTipus: string;
  vanSzoveg: boolean;
}

export interface DokumentumResponse extends DokumentumListItem {
  /** a kinyert szöveg; null, ha még nincs (OCR/kinyerés nem futott) */
  szoveg: string | null;
  /** a nyers fájl letöltési útja (a naplóban EXPORT) */
  fajlUrl: string;
}

// ---------- felvetel + megszolalas ----------
/** egyeb: hozzátartozó vagy harmadik fél (a Python `egyéb`, a backend `OTHER`) */
export type Beszelo = 'orvos' | 'beteg' | 'egyeb';

export interface FelvetelResponse {
  id: number;
  patientId: number;
  mod: 'surgossegi' | 'echo_diktalas';
  kezdes: string; // ISO datetime
  hosszMp: number;
}

export interface MegszolalasResponse {
  id: number;
  felvetelId: number;
  /** a turn-sorszám (backend turnId); a forrás-hivatkozás erre mutat */
  sorszam: number;
  beszelo: Beszelo;
  kezdetMp: number;
  szoveg: string;
}

// ---------- labor ----------
export interface LaborMeres {
  datum: string;
  ertek: number;
  /** a lelet, amelyből a mérés származik; null: dokumentum nélkül rögzítve */
  dokumentumId: number | null;
}

export interface LaborParameter {
  /** egyedi kulcs: név + mértékegység (különböző egységű mérések nem keverednek) */
  kod: string;
  nev: string;
  mertekegyseg: string;
  refAlso: number | null;
  refFelso: number | null;
  /** true: céltartomány (pl. INR terápiás cél), false: referenciatartomány */
  celtartomany: boolean;
  meresek: LaborMeres[];
}

export interface LaborIdosor {
  patientId: number;
  parameterek: LaborParameter[];
}

// ---------- chat (NINCS még backend-végpont) ----------
export interface ChatPont {
  szoveg: string;
  forrasok: Forras[]; // üres = forrás nélküli következtetés
}

export interface ChatValasz {
  id: string;
  patientId: number;
  bizonytalansag: Bizonytalansag;
  pontok: ChatPont[];
  kapcsoltSzabalyok: { allitasId: number; cim: string; sulyossag: Sulyossag }[];
}

export interface ChatUzenet {
  id: string;
  szerep: 'orvos' | 'ai';
  szoveg?: string;
  valasz?: ChatValasz;
}

// ---------- hiba ----------
export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/** Egy képesség, amihez még nincs backend-végpont. A UI ezt külön jelzi. */
export class NotAvailableError extends Error {
  constructor(feature: string) {
    super(`${feature}: ehhez még nincs backend-végpont`);
  }
}
