import type { LaborJel, LaborParameter } from '../api/types';
import { formatSzam } from './format';

// Laborérték megjelenítése és tartomány-kiértékelése — a Labor-idősor és a Klinikai státusz
// "Fő eltérések" chipjei is ezt használják, hogy a kettő sose mondjon mást.

/** Egy érték tizedesjegyei (a backend legfeljebb 4-et tárol) */
export const tizedesOf = (v: number) => {
  const s = String(v);
  const i = s.indexOf('.');
  return i < 0 ? 0 : Math.min(4, s.length - i - 1);
};

/**
 * A megjelenítés annyi tizedesjegyet mutat, amennyi a mért értékekben és a
 * határokban ténylegesen van — egy 0,123-as TSH nem jelenhet meg „0,1”-ként
 * (CLAUDE.md: nem kerekítünk csendben, a megjelenítésben sem).
 */
export const tizedes = (p: LaborParameter) =>
  Math.max(
    0,
    ...p.meresek.map((m) => tizedesOf(m.ertek)),
    ...(p.refAlso !== null ? [tizedesOf(p.refAlso)] : []),
    ...(p.refFelso !== null ? [tizedesOf(p.refFelso)] : []),
  );
/**
 * Tartományon kívül? Cenzorált eredménynél (">90") csak akkor jelzünk, ha a jelből biztosan
 * következik — pl. ">90" és ref. felső 60 → felett; ">90" és ref. felső 120 → nem tudható.
 */
export function irany(p: LaborParameter, v: number, jel: LaborJel | null = null): 'alatt' | 'felett' | null {
  const alatt =
    p.refAlso !== null &&
    (jel === null ? v < p.refAlso : jel === '<' ? v <= p.refAlso : jel === '<=' ? v < p.refAlso : false);
  const felett =
    p.refFelso !== null &&
    (jel === null ? v > p.refFelso : jel === '>' ? v >= p.refFelso : jel === '>=' ? v > p.refFelso : false);
  return alatt ? 'alatt' : felett ? 'felett' : null;
}

export const kivul = (p: LaborParameter, v: number, jel: LaborJel | null = null) => irany(p, v, jel) !== null;

export function statusz(p: LaborParameter, v: number, jel: LaborJel | null = null) {
  const i = irany(p, v, jel);
  if (i === 'alatt') return p.celtartomany ? '↓ cél alatt' : '↓ ref. alatt';
  if (i === 'felett') return p.celtartomany ? '↑ cél felett' : '↑ ref. felett';
  return null;
}

const JEL: Record<LaborJel, string> = { '<': '<', '<=': '≤', '>': '>', '>=': '≥' };

/** Megjelenített érték: cenzoráltnál a jellel együtt (">90"), hogy ne tűnjön pontos mérésnek */
export const ertekSzoveg = (v: number, d: number, jel: LaborJel | null) => `${jel ? JEL[jel] : ''}${formatSzam(v, d)}`;
