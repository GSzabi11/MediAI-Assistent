/**
 * A PubMed-keresőkifejezés fogalmakra bontva (docs/literature-research.md, „Kulcsszavak”).
 *
 * Ugyanaz a szerkezet, amit a docproc `research.build_query` rak össze: fogalmanként
 * `(Heading[MeSH Terms] OR Heading[tiab] OR szinonima[tiab] …)`, a fogalmak AND-del, idézőjel nélkül.
 * A fogalmak között az orvos AND helyett OR-t is választhat (`elotte`); vegyes operátornál a PubMed
 * balról jobbra értékel: `A AND B OR C` = `(A AND B) OR C`.
 * A fogalmak nem tárolódnak külön: a kifejezésből bontjuk vissza (`bont`), és a chipek minden
 * változásakor újra összerakjuk (`epit`) — így egyetlen igazság marad, a kifejezés. Kézzel átírt,
 * ettől eltérő szintaxisú kifejezés nem bontható (`null`): akkor csak a szövegmező szerkeszthető.
 * A betegazonosító-szűrés nem itt van: a Spring `OutboundQueryGate` minden keresésnél ellenőriz.
 */
export interface Fogalom {
  /** MeSH-fejléc: `[MeSH Terms]`-ként és `[tiab]`-ként is bekerül (mint a build_query-ben) */
  mesh: string | null;
  /** szabad szavas alakok, `[tiab]` */
  szinonimak: string[];
  /** az előző fogalomhoz kötő operátor (az elsőnél nincs jelentése) */
  elotte: Operator;
}

export type Operator = 'AND' | 'OR';

// a research.py QUERY_TERM_MAX_CHARS-a
export const KIFEJEZES_HOSSZ = 60;
// a Spring ResearchCreateRequest korlátja
export const KIFEJEZES_MAX = 1000;

const MESH = /^(?:mesh terms|mesh|mh|majr)$/i;
const TIAB = /^(?:tiab|title\/abstract|tw|ti|ab)$/i;
// a PubMed-szintaxis jelei egy kulcsszóban nem maradhatnak (a research.py _SYNTAX-a)
const SZINTAXIS = /[[\]()"*:]|\b(?:AND|OR|NOT)\b/g;

/** Egy kulcsszó tisztítva, ahogy a build_query `_term`-je (a demográfiai szűrés nélkül: azt az orvos dönti el). */
export function tisztit(nyers: string): string {
  return nyers
    .replace(SZINTAXIS, ' ')
    .split(/\s+/)
    .filter(Boolean)
    .join(' ')
    .replace(/^[ ,;.]+|[ ,;.]+$/g, '')
    .slice(0, KIFEJEZES_HOSSZ);
}

/** A legfelső szintű elválasztók mentén darabol (zárójelen belül nem); a darabok és az előttük álló operátor. */
function darabol(s: string, ops: Operator[]): { resz: string; elotte: Operator }[] | null {
  const reszek: { resz: string; elotte: Operator }[] = [];
  let melyseg = 0;
  let kezdet = 0;
  let elotte: Operator = 'AND';
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '(') melyseg++;
    else if (c === ')') {
      melyseg--;
      if (melyseg < 0) return null;
    } else if (melyseg === 0) {
      const op = ops.find((o) => s.startsWith(` ${o} `, i));
      if (op) {
        reszek.push({ resz: s.slice(kezdet, i).trim(), elotte });
        elotte = op;
        kezdet = i + op.length + 2;
        i += op.length + 1;
      }
    }
  }
  if (melyseg !== 0) return null;
  reszek.push({ resz: s.slice(kezdet).trim(), elotte });
  return reszek;
}

/** A kifejezés fogalmai; `null`, ha nem a build_query szerkezetét követi. */
export function bont(kifejezes: string): Fogalom[] | null {
  const q = kifejezes.split(/\s+/).filter(Boolean).join(' ');
  if (!q) return [];
  const csoportok = darabol(q, ['AND', 'OR']);
  if (!csoportok) return null;
  const fogalmak: Fogalom[] = [];
  for (const { resz: cs, elotte } of csoportok) {
    let belso = cs;
    if (belso.startsWith('(') && belso.endsWith(')')) belso = belso.slice(1, -1).trim();
    const tagok = darabol(belso, ['OR']);
    if (!tagok) return null;
    const f: Fogalom = { mesh: null, szinonimak: [], elotte };
    for (const { resz: tag } of tagok) {
      const m = /^"?([^"[\]()]+?)"?\s*\[([^\]]+)\]$/.exec(tag);
      if (!m) return null;
      const szo = tisztit(m[1]);
      if (!szo) return null;
      if (MESH.test(m[2].trim())) {
        if (f.mesh !== null) return null; // fogalmanként egy fejléc
        f.mesh = szo;
      } else if (TIAB.test(m[2].trim())) {
        // a fejléc [tiab]-alakja nem külön szinonima
        if (szo.toLowerCase() !== f.mesh?.toLowerCase()) f.szinonimak.push(szo);
      } else {
        return null;
      }
    }
    fogalmak.push(f);
  }
  return fogalmak;
}

/** A fogalmakból a kifejezés — a build_query szabályaival (üres fogalom kimarad, ismétlődő alak egyszer);
 * a fogalmak között a választott operátor. */
export function epit(fogalmak: Fogalom[]): string {
  const latott = new Set<string>();
  let kifejezes = '';
  for (const f of fogalmak) {
    const tagok: string[] = [];
    const mesh = f.mesh ? tisztit(f.mesh) : '';
    if (mesh) tagok.push(`${mesh}[MeSH Terms]`);
    for (const szo of [mesh, ...f.szinonimak.map(tisztit)]) {
      if (szo && !latott.has(szo.toLowerCase())) {
        latott.add(szo.toLowerCase());
        tagok.push(`${szo}[tiab]`);
      }
    }
    if (!tagok.length) continue;
    const csoport = tagok.length === 1 ? tagok[0] : `(${tagok.join(' OR ')})`;
    kifejezes = kifejezes ? `${kifejezes} ${f.elotte} ${csoport}` : csoport;
  }
  return kifejezes;
}
