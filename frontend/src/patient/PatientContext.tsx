import { createContext, useContext } from 'react';
import type {
  ClaimResponse,
  DokumentumListItem,
  FelvetelResponse,
  MegszolalasResponse,
  PatientResponse,
} from '../api/types';

/**
 * Az aktív beteg és a hozzá tartozó forrás-metaadat. Minden betegoldali
 * komponens innen olvassa, KI az aktív beteg — soha nem URL-ből vagy propból
 * rakja össze újra. Így egyetlen igazságforrás van a beteg-azonosításra.
 */
export interface PatientCtx {
  patient: PatientResponse;
  dokumentumok: DokumentumListItem[];
  felvetelek: FelvetelResponse[];
  /** `${felvetelId}:${turn-sorszám}` → megszólalás (a forrás-chipekhez); kulcs: megszolalasKulcs() */
  megszolalasByTurn: Map<string, MegszolalasResponse>;
  allitasok: ClaimResponse[];
  /**
   * Csendes újratöltés mentés után (dokumentum, felvétel, állítás): a képernyő nem
   * ugrik vissza „betöltés…”-re. Hibánál kivételt dob — a hívó jelzi.
   */
  frissit: () => Promise<void>;
}

/** A turn-sorszám csak felvételen belül egyedi, ezért a kulcs a felvételt is tartalmazza. */
export const megszolalasKulcs = (felvetelId: number, turn: number) => `${felvetelId}:${turn}`;

export const PatientContext = createContext<PatientCtx | null>(null);

export function usePatient(): PatientCtx {
  const ctx = useContext(PatientContext);
  if (!ctx) throw new Error('usePatient: nincs aktív beteg (PatientContext hiányzik)');
  return ctx;
}
