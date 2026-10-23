import type { Forras } from '../api/types';

/** 123456789 → "123 456 789"; null → azonosítatlan beteg (SBO) */
export const formatTaj = (taj: string | null) =>
  taj ? taj.replace(/\D/g, '').replace(/(\d{3})(?=\d)/g, '$1 ') : 'nincs TAJ';

/**
 * Időpont (a backend UTC-ben, `…Z`-vel adja) → helyi (budapesti) idő szerinti Date.
 * Puszta dátumot (YYYY-MM-DD) nem alakítunk: az nem pillanat, hanem naptári nap.
 */
const helyi = (iso: string) => new Date(iso);
const isDatumIdo = (iso: string) => iso.length > 10 && iso.includes('T');
const ket = (n: number) => String(n).padStart(2, '0');

/** 2026-03-12 → "2026.03.12."; időpontnál a helyi naptári nap */
export const formatDatum = (iso: string) => {
  if (!isDatumIdo(iso)) return iso.slice(0, 10).replaceAll('-', '.') + '.';
  const d = helyi(iso);
  return `${d.getFullYear()}.${ket(d.getMonth() + 1)}.${ket(d.getDate())}.`;
};

/** 2026-03-12 → "’26.03" */
export const formatHonap = (iso: string) => `’${iso.slice(2, 4)}.${iso.slice(5, 7)}`;

/** időpont → "2026.09.24. 17:45" helyi idő szerint */
export const formatDatumIdo = (iso: string) => {
  const ido = formatIdo(iso);
  return ido ? `${formatDatum(iso)} ${ido}` : formatDatum(iso);
};

/** 1536 → "1,5 kB" */
export const formatMeret = (bajt: number) =>
  bajt < 1024
    ? `${bajt} B`
    : bajt < 1024 * 1024
      ? `${formatSzam(bajt / 1024, 1)} kB`
      : `${formatSzam(bajt / 1024 / 1024, 1)} MB`;

/** <input type="datetime-local"> értéke (helyi idő) → ISO időpont (UTC), amit a backend OffsetDateTime-ként fogad */
export const helyiToIso = (local: string) => new Date(local).toISOString();

/** most, <input type="datetime-local"> formátumban */
export const mostHelyi = () => {
  const d = new Date();
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
};

/**
 * Magyar számbevitel szigorú értelmezése: "5,6" és "5.6" is jó, de cenzorált vagy
 * kvalitatív érték ("<0,5", "negatív") NEM — azt elutasítjuk, nem kerekítjük (CLAUDE.md).
 * null: nem szám. A tizedesjegyek számát a backend oszlopa szabja meg.
 */
export function parseSzam(s: string, maxTizedes: number): number | null {
  const t = s.trim().replace(',', '.');
  const re = new RegExp(`^-?\\d+(\\.\\d{1,${maxTizedes}})?$`);
  return re.test(t) ? Number(t) : null;
}

/** időpont → "HH:MM" helyi idő szerint; puszta dátumnál üres */
export const formatIdo = (iso: string) => {
  if (!isDatumIdo(iso)) return '';
  const d = helyi(iso);
  return `${ket(d.getHours())}:${ket(d.getMinutes())}`;
};

export const formatMp = (mp: number) => `${Math.floor(mp / 60)}:${String(mp % 60).padStart(2, '0')}`;

export const formatSzam = (n: number, tizedes = 0) => n.toFixed(tizedes).replace('.', ',');

export function eletkor(szuletesiDatum: string, ma = new Date()) {
  const sz = new Date(szuletesiDatum);
  let kor = ma.getFullYear() - sz.getFullYear();
  const m = ma.getMonth() - sz.getMonth();
  if (m < 0 || (m === 0 && ma.getDate() < sz.getDate())) kor--;
  return kor;
}

/**
 * Forrás → URL a betegoldalon belül. Az URL hordozza a kiemelést, így a
 * hivatkozás megosztható, és a vissza gomb is működik.
 */
export function forrasLink(patientId: number, f: Forras) {
  if (f.tipus === 'dokumentum') {
    return `/betegek/${patientId}?nezet=dokumentum&dok=${f.dokumentumId}&kezdet=${f.kezdet}&veg=${f.veg}`;
  }
  if (f.tipus === 'kep') {
    return `/betegek/${patientId}?nezet=dokumentum&dok=${f.dokumentumId}`;
  }
  if (f.tipus === 'labor') {
    return `/betegek/${patientId}?nezet=labor&meres=${f.laborId}`;
  }
  if (f.tipus === 'gyogyszer') {
    return `/betegek/${patientId}?nezet=gyogyszer`;
  }
  return `/betegek/${patientId}?nezet=atirat&felvetel=${f.felvetelId}&turn=${f.turnIds.join(',')}`;
}
