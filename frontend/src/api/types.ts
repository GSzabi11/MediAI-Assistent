/**
 * Nézet-típusok — ezt látják a komponensek. A backend DTO-k (dto.ts) ezekre
 * képeződnek le az api-rétegben (mappers.ts); a komponensek a backend
 * mezőneveit nem ismerik.
 */
import type { Szerepkor } from './currentUser';

// ---------- patient ----------
export interface PatientResponse {
  id: number;
  nev: string;
  /** 9 számjegy, formázás nélkül; null: azonosítatlan / külföldi beteg (SBO) */
  taj: string | null;
  szuletesiDatum: string; // ISO: YYYY-MM-DD
  anyjaNeve: string | null;
  lakcim: string | null;
}

/** Új beteg felvétele. TAJ nélkül: azonosítatlan / külföldi beteg (SBO). */
export interface UjBeteg {
  nev: string;
  taj: string | null;
  anyjaNeve: string;
  szuletesiDatum: string;
  lakcim: string | null;
}

export type PatientSearch =
  { mod: 'taj'; taj: string } | { mod: 'termeszetes'; nev: string; anyjaNeve: string; szuletesiDatum: string };

// ---------- felhasználó + hangprofil ----------
export interface Felhasznalo {
  id: number;
  felhasznalonev: string;
  email: string;
  szerepkor: Szerepkor;
  letrehozva: string;
}

export interface Hangprofil {
  id: number;
  felhasznaloId: number;
  wavUt: string;
  embeddingModell: string;
  kuszob: number | null;
  aktiv: boolean;
  nettoBeszedMp: number | null;
  mikrofon: string | null;
  rogzitve: string;
  letrehozva: string;
}

export interface UjHangprofil {
  felhasznaloId: number;
  wavUt: string;
  embeddingModell: string;
  kuszob: number | null;
  nettoBeszedMp: number | null;
  mikrofon: string | null;
  rogzitve: string; // ISO időpont
}

// ---------- ellátás ----------
export type EllatasTipus = 'sbo' | 'ambulans' | 'echo';

export interface Ellatas {
  id: number;
  patientId: number;
  orvosId: number;
  tipus: EllatasTipus;
  kezdet: string;
  /** null: még nyitott */
  veg: string | null;
}

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

/** Egy döntés az előzményből (a legfrissebb az érvényes) */
export interface JovahagyasElozmeny extends JovahagyasResponse {
  id: number;
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
  /** HOGY: az előállító olvasható leírása (modell/kinyerő/szabály) */
  eloallito: string;
  /** a legfrissebb döntés utáni érvényes szöveg (módosításnál az orvosé) */
  ervenyesSzoveg: string;
  klinikaiIdo: string;
  forrasok: Forras[];
  jovahagyas: JovahagyasResponse | null;
}

/** Kézzel kijelölt, szó szerinti idézet állításként (RETRIEVED_FACT) */
export interface UjIdezet {
  patientId: number;
  szoveg: string;
  cimke: string | null;
  sulyossag: Sulyossag;
  forras: Forras;
}

// ---------- dokumentum + RAG-darab ----------
export type DokumentumTipus = 'labor' | 'ambulans' | 'kepalkotas';

/** PDF: feltöltés után 'feldolgozas', a Python-feldolgozás után 'kesz' vagy 'hiba' */
export type FeldolgozasAllapot = 'feldolgozas' | 'kesz' | 'hiba';

export interface DokumentumListItem {
  id: number;
  patientId: number;
  /** null: a feldolgozás még nem sorolta be (vagy hibával leállt) */
  tipus: DokumentumTipus | null;
  cim: string;
  allapot: FeldolgozasAllapot;
  feldolgozasiHiba: string | null;
  /** amit a feldolgozás tudatosan kihagyott (pl. cenzorált laborérték) */
  figyelmeztetesek: string[];
  /** HOGY: a kinyerő modell; kézi feltöltésnél null */
  kinyero: { modell: string; seed: number; promptHash: string; verzio: string } | null;
  osztaly: string | null;
  /** megjelenítéshez: a klinikai dátum, ha nincs, a feltöltés ideje */
  datum: string;
  /** a lelet klinikai dátuma — ebből pótolja a backend a hiányzó mérési/klinikai időt */
  klinikaiDatum: string | null;
  mimeTipus: string;
  meretBajt: number;
  vanSzoveg: boolean;
}

export interface DokumentumResponse extends DokumentumListItem {
  /** a kinyert szöveg; null, ha még nincs (OCR/kinyerés nem futott) */
  szoveg: string | null;
}

export interface UjDokumentum {
  patientId: number;
  fajl: File;
  /** PDF-nél null: a típust és a dátumot az automatikus feldolgozás határozza meg */
  tipus: DokumentumTipus | null;
  klinikaiDatum: string | null;
}

export interface RagDarab {
  id: number;
  dokumentumId: number;
  szoveg: string;
  kezdet: number;
  veg: number;
  embeddingModell: string;
}

// ---------- felvetel + megszolalas ----------
/** egyeb: hozzátartozó vagy harmadik fél (a Python `egyéb`, a backend `OTHER`) */
export type Beszelo = 'orvos' | 'beteg' | 'egyeb';
export type FelvetelAllapot = 'rogzites' | 'feldolgozas' | 'kesz' | 'hiba';

export interface FelvetelResponse {
  id: number;
  patientId: number;
  orvosId: number;
  ellatasId: number | null;
  mod: 'surgossegi' | 'echo_diktalas';
  allapot: FelvetelAllapot;
  hangfajl: string;
  asrModell: string;
  kezdes: string; // ISO datetime
  hosszMp: number;
}

export interface UjFelvetel {
  patientId: number;
  ellatasId: number | null;
  mod: FelvetelResponse['mod'];
  hangfajl: string;
  asrModell: string;
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
/** Cenzorált eredmény jele (pl. EGFR ">90"): az `ertek` ekkor a határ, nem pontos mérés */
export type LaborJel = '<' | '<=' | '>' | '>=';

export interface LaborMeres {
  id: number;
  datum: string;
  ertek: number;
  jel: LaborJel | null;
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

/** Kvalitatív (szöveges) eredmény, pl. vizelet: Fehérje "Negatív" — grafikonra nem kerül */
export interface LaborSzovegesMeres {
  id: number;
  datum: string;
  szoveg: string;
  dokumentumId: number | null;
}

export interface LaborSzovegesParameter {
  kod: string;
  nev: string;
  mertekegyseg: string;
  /** szöveges referencia ("Negatív"); a legutóbbi mérésé */
  refSzoveg: string | null;
  meresek: LaborSzovegesMeres[];
}

export interface LaborIdosor {
  patientId: number;
  parameterek: LaborParameter[];
  szovegesek: LaborSzovegesParameter[];
}

/** MERRE a PDF-ben: oldal + a laphoz normált (0–1) téglalap — a kinyeréskor rögzítve */
export interface ForrasDoboz {
  oldal: number;
  x: number;
  y: number;
  szelesseg: number;
  magassag: number;
}

/** Egy mérés minden adata (GET /lab-results/{id}) */
export interface LaborReszlet {
  id: number;
  patientId: number;
  dokumentumId: number | null;
  csoport: string | null;
  /** egységes név (a trendben ez látszik) */
  nev: string;
  /** a leleten álló eredeti név */
  leletNev: string;
  /** null: kvalitatív eredmény (szoveg) */
  ertek: number | null;
  jel: LaborJel | null;
  szoveg: string | null;
  mertekegyseg: string | null;
  refAlso: number | null;
  refFelso: number | null;
  refSzoveg: string | null;
  idopont: string;
  kod: string | null;
  /** a sor helye a lelet PDF-jében (kiemeléshez); null, ha nem ismert */
  forrasDoboz: ForrasDoboz | null;
}

export interface UjLaborSor {
  csoport: string | null;
  nev: string;
  ertek: number;
  mertekegyseg: string | null;
  refAlso: number | null;
  refFelso: number | null;
  /** null: a dokumentum klinikai dátuma */
  idopont: string | null;
  kod: string | null;
}

export interface UjLaborPanel {
  patientId: number;
  dokumentumId: number | null;
  sorok: UjLaborSor[];
}

// ---------- gyógyszer + allergia ----------
export interface Gyogyszer {
  id: number;
  patientId: number;
  nev: string;
  hatoanyag: string | null;
  dozis: number | null;
  dozisEgyseg: string | null;
  alkalmazas: string | null;
  forrasAllitasId: number | null;
  aktiv: boolean;
  /** a dokumentum-feldolgozás írta be (nem kézi rögzítés) */
  automatikus: boolean;
  /** false: automatikus tétel, amelynek forrás-állítását orvos még nem fogadta el */
  ellenorzott: boolean;
}

export type UjGyogyszer = Omit<Gyogyszer, 'id' | 'aktiv' | 'automatikus' | 'ellenorzott'>;

export interface Allergia {
  id: number;
  patientId: number;
  anyag: string;
  forrasAllitasId: number | null;
  automatikus: boolean;
  ellenorzott: boolean;
}

export type UjAllergia = Omit<Allergia, 'id' | 'automatikus' | 'ellenorzott'>;

// ---------- hozzáférési napló ----------
export interface NaploBejegyzes {
  id: number;
  felhasznaloId: number | null;
  patientId: number | null;
  muvelet: 'READ' | 'WRITE' | 'EXPORT';
  eroforras: string;
  httpStatus: number;
  idopont: string;
  ip: string | null;
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
