import type { FelvetelAllapot } from '../api/types';

/** A felvétel állapotgépének (backend RecordingStatus) magyar feliratai */
export const allapotFelirat: Record<FelvetelAllapot, string> = {
  rogzites: 'Rögzítés alatt',
  feldolgozas: 'Feldolgozás alatt',
  kesz: 'Kész',
  hiba: 'Sikertelen',
};
