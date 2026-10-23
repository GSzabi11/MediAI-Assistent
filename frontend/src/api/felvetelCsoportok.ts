/**
 * A felvétel átiratából kinyert csoportok (panaszok, diagnózisok, beutalók, vizsgálati megállapítások,
 * javasolt gyógyszerek, javaslatok / kontroll) — V34, docs/voice-module.md „Csoportok”. DTO, nézetmodell,
 * leképezés és hívások egy fájlban: önálló funkció, a közös index.ts-t nem bővíti.
 */
import { getCurrentUser } from './currentUser';
import { http } from './http';

// ---------- backend DTO ----------

export type FindingCategoryDto =
  | 'COMPLAINT'
  | 'DIAGNOSIS'
  | 'REFERRAL'
  | 'EXAM_FINDING'
  | 'MEDICATION'
  | 'RECOMMENDATION';

interface FindingDto {
  id: number;
  recordingId: number;
  extractionId: number | null;
  category: FindingCategoryDto;
  origin: 'MODEL' | 'DOCTOR';
  text: string;
  bnoCode: string | null;
  originalText: string;
  originalBnoCode: string | null;
  edited: boolean;
  turnIds: number[];
  flag: string | null;
  createdAt: string;
  editedAt: string | null;
}

interface ExtractionDto {
  id: number;
  status: 'PROCESSING' | 'DONE' | 'FAILED';
  requestedAt: string;
  error: string | null;
  warnings: string[];
  modelName: string | null;
}

interface FindingsDto {
  extraction: ExtractionDto | null;
  itemsFromExtractionId: number | null;
  items: FindingDto[];
}

// ---------- nézetmodell ----------

export const csoportok: { kategoria: FindingCategoryDto; cim: string; bno: boolean; pelda: string }[] = [
  { kategoria: 'COMPLAINT', cim: 'Panaszok', bno: true, pelda: 'pl. szorító mellkasi fájdalom' },
  { kategoria: 'DIAGNOSIS', cim: 'Diagnózisok', bno: true, pelda: 'pl. akut koronária szindróma gyanúja' },
  { kategoria: 'REFERRAL', cim: 'Beutalók', bno: false, pelda: 'pl. kardiológiai konzílium' },
  { kategoria: 'EXAM_FINDING', cim: 'Vizsgálati megállapítások', bno: false, pelda: 'pl. RR 165/95 Hgmm' },
  { kategoria: 'MEDICATION', cim: 'Javasolt gyógyszerek', bno: false, pelda: 'pl. Aspirin 100 mg' },
  { kategoria: 'RECOMMENDATION', cim: 'Javaslatok / kontroll', bno: false, pelda: 'pl. kontroll 2 hét múlva' },
];

export interface CsoportElem {
  id: number;
  kategoria: FindingCategoryDto;
  /** ai: a MedGemma javasolta; orvos: kézzel hozzáadva */
  forras: 'ai' | 'orvos';
  /** a megjelenített szöveg (az orvos szerkesztése, ha van) */
  szoveg: string;
  bno: string | null;
  eredetiSzoveg: string;
  eredetiBno: string | null;
  szerkesztett: boolean;
  turnIds: number[];
  /** determinisztikus ellenőrzés jelzése (pl. az átiratban nem szereplő szám) */
  jelzes: string | null;
}

export interface Csoportok {
  futas: {
    id: number;
    allapot: 'feldolgozas' | 'kesz' | 'hiba';
    kerve: string;
    hiba: string | null;
    figyelmeztetesek: string[];
    modell: string | null;
  } | null;
  elemek: CsoportElem[];
}

const toElem = (f: FindingDto): CsoportElem => ({
  id: f.id,
  kategoria: f.category,
  forras: f.origin === 'MODEL' ? 'ai' : 'orvos',
  szoveg: f.text,
  bno: f.bnoCode,
  eredetiSzoveg: f.originalText,
  eredetiBno: f.originalBnoCode,
  szerkesztett: f.edited,
  turnIds: f.turnIds ?? [],
  jelzes: f.flag,
});

const toCsoportok = (d: FindingsDto): Csoportok => ({
  futas: d.extraction && {
    id: d.extraction.id,
    allapot:
      d.extraction.status === 'PROCESSING' ? 'feldolgozas' : d.extraction.status === 'DONE' ? 'kesz' : 'hiba',
    kerve: d.extraction.requestedAt,
    hiba: d.extraction.error,
    figyelmeztetesek: d.extraction.warnings ?? [],
    modell: d.extraction.modelName,
  },
  elemek: d.items.map(toElem),
});

function userId(): number {
  const u = getCurrentUser();
  if (!u) throw new Error('Nincs bejelentkezett felhasználó.');
  return u.id;
}

// ---------- hívások ----------

export const csoportApi = {
  /** GET /recordings/{id}/findings — a legutóbbi futás + az érvényes elemek */
  list: async (felvetelId: number) => toCsoportok(await http.get<FindingsDto>(`/recordings/${felvetelId}/findings`)),

  /** POST /recordings/{id}/findings/extractions (202) — újrakinyerés; az AI-elemek cserélődnek */
  kinyer: async (felvetelId: number) =>
    void (await http.post(`/recordings/${felvetelId}/findings/extractions`, { userId: userId() })),

  /** POST /recordings/{id}/findings — az orvos új eleme (kijelölésből a megszólalással) */
  hozzaad: async (
    felvetelId: number,
    kategoria: FindingCategoryDto,
    szoveg: string,
    bno: string | null,
    turnIds: number[] = [],
  ) =>
    toElem(
      await http.post<FindingDto>(`/recordings/${felvetelId}/findings`, {
        userId: userId(),
        category: kategoria,
        text: szoveg.trim(),
        bnoCode: bno?.trim() || null,
        turnIds,
      }),
    ),

  /** PUT /recordings/{id}/findings/{fid} — szerkesztés; az eredeti megmarad */
  szerkeszt: async (felvetelId: number, elemId: number, szoveg: string, bno: string | null) =>
    toElem(
      await http.put<FindingDto>(`/recordings/${felvetelId}/findings/${elemId}`, {
        userId: userId(),
        text: szoveg.trim(),
        bnoCode: bno?.trim() || null,
      }),
    ),

  /** POST /recordings/{id}/findings/{fid}/delete — logikai törlés */
  torol: async (felvetelId: number, elemId: number) =>
    toElem(await http.post<FindingDto>(`/recordings/${felvetelId}/findings/${elemId}/delete`, { userId: userId() })),
};
