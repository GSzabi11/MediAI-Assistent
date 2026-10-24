/**
 * Gyógyszer-interakció (V35, docs/drug-interaction.md): a referenciaadat (PUPHA + DDInter) állapota,
 * a beteg gyógyszereinek lefedettsége és az ad hoc ellenőrzés. A szedett gyógyszerek közötti
 * interakciók állításként jönnek (MEDICATION forrás) — azokat a PatientContext `allitasok`-a hozza.
 * DTO és hívások egy fájlban: önálló funkció, a közös index.ts-t nem bővíti.
 */
import { http } from './http';
import type { SeverityDto } from './dto';

export type FeloldasAllapot = 'RESOLVED' | 'PARTIAL' | 'NO_DATA' | 'NOT_FOUND';

export interface Hatoanyag {
  id: number;
  name: string;
}

export interface Feloldas {
  input: string;
  status: FeloldasAllapot;
  products: string[];
  atcCodes: { code: string; name: string | null }[];
  ingredients: Hatoanyag[];
  unresolved: string[];
  /** felismert helyi alkalmazás (a DDInter külön hatóanyagként vezeti), null: szisztémás */
  route: 'ophthalmic' | 'topical' | 'nasal' | 'otic' | null;
}

export interface ReferenciaAllapot {
  loaded: boolean;
  running: boolean;
  runningSince: string | null;
  lastRun: { startedAt: string; finishedAt: string; success: boolean; notes: string[]; error: string | null } | null;
  imports: { source: 'PUPHA' | 'DDINTER' | 'ATC_MAP'; versionLabel: string; rowCount: number; importedAt: string; note: string | null }[];
}

export interface Lefedettseg {
  loaded: boolean;
  medications: { medicationId: number; name: string; resolution: Feloldas }[];
}

export interface HatoanyagPar {
  ingredientA: string;
  ingredientB: string;
  level: 'Major' | 'Moderate' | 'Minor' | 'Unknown';
  levelHu: string;
}

export interface EllenorzesEredmeny {
  loaded: boolean;
  entered: { medicationId: null; label: string; resolution: Feloldas }[];
  current: { medicationId: number; label: string; resolution: Feloldas }[];
  findings: {
    labelA: string;
    medicationIdA: number | null;
    labelB: string;
    medicationIdB: number | null;
    severity: SeverityDto;
    interactions: HatoanyagPar[];
    text: string;
  }[];
}

export const interakcioApi = {
  allapot: () => http.get<ReferenciaAllapot>('/drug-reference/status'),
  frissites: () => http.post<ReferenciaAllapot>('/drug-reference/refresh'),
  javaslatok: (q: string) => http.get<string[]>(`/drug-reference/suggestions?q=${encodeURIComponent(q)}`),
  lefedettseg: (patientId: number) => http.get<Lefedettseg>(`/patients/${patientId}/drug-interactions/coverage`),
  ellenoriz: (patientId: number, drugs: string[]) =>
    http.post<EllenorzesEredmeny>(`/patients/${patientId}/drug-interactions/check`, { drugs }),
};
