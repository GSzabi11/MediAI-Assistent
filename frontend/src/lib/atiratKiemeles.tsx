import type { ReactNode } from 'react';

/**
 * Kiemelések az átirat buborékjaiban — determinisztikus szövegegyezéssel, nem modellel:
 *  - kulcsszó: kis/nagybetű-független, szótő-alapú egyezés (a magyar ragozás miatt: „mellkasi
 *    fájdalom” → „mellkasi fájdalmat” is);
 *  - érték (echo módban): szám + mértékegység, hogy a diktált mérések külön figyelmet kapjanak.
 * A szöveg tartalma nem változik (csak <mark>-ok kerülnek bele), így a kijelölés-alapú idézés
 * (lib/kijeles) ugyanazt a szöveget látja.
 */

const UNIT =
  'milliméter|centiméter per szekundum|méter per szekundum|centiméter|méter|milliliter|liter|milliszekundum|szekundum|' +
  'másodperc|százalék|Hgmm|mmHg|mm|cm/s|m/s|cm|ml|%|ütés per perc|per perc';
const VALUE = new RegExp(`\\d+(?:[.,]\\d+)?(?:\\s*(?:${UNIT}))?`, 'gi');

type Talalat = { start: number; end: number; tipus: 'kulcsszo' | 'ertek' };

function escape(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// Ugyanaz a szabály, mint a Python kulcsszó-visszakeresésében (ai-service/voice/keywords.py):
// a kifejezés minden szavának töve (első 5 betű) szókezdetként, egymás után
const STEM_LEN = 5;

export function kulcsszoTalalatok(text: string, kulcsszavak: string[]): Talalat[] {
  const out: Talalat[] = [];
  for (const k of kulcsszavak) {
    const words = k.toLowerCase().match(/[\p{L}\d]+/gu);
    if (!words || words.length > 4) continue;
    const parts = words.map((w) => `${escape(w.slice(0, STEM_LEN))}\\p{L}*`);
    const re = new RegExp(`(?<![\\p{L}\\d])${parts.join('[\\s,.-]+')}`, 'giu');
    for (const m of text.matchAll(re)) out.push({ start: m.index, end: m.index + m[0].length, tipus: 'kulcsszo' });
  }
  return out;
}

function ertekTalalatok(text: string): Talalat[] {
  return [...text.matchAll(VALUE)].map((m) => ({ start: m.index, end: m.index + m[0].length, tipus: 'ertek' }));
}

export function kiemel(text: string, kulcsszavak: string[], ertekek: boolean): ReactNode[] {
  const talalatok = [...kulcsszoTalalatok(text, kulcsszavak), ...(ertekek ? ertekTalalatok(text) : [])].sort(
    (a, b) => a.start - b.start || b.end - a.end,
  );
  const nodes: ReactNode[] = [];
  let pos = 0;
  for (const t of talalatok) {
    if (t.start < pos) continue; // átfedés: az előbb kezdődő / hosszabb nyer
    if (t.start > pos) nodes.push(text.slice(pos, t.start));
    nodes.push(
      <mark key={`${t.start}-${t.tipus}`} className={t.tipus === 'kulcsszo' ? 'mark-kulcsszo' : 'mark-ertek'}>
        {text.slice(t.start, t.end)}
      </mark>,
    );
    pos = t.end;
  }
  if (pos < text.length) nodes.push(text.slice(pos));
  return nodes;
}
