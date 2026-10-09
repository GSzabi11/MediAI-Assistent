/**
 * Szövegkijelölés → karaktertartomány Unicode code pointban (mint a backend
 * Document.textSpan és a Python). A böngésző UTF-16 egységben ad pozíciót; ezt
 * itt számoljuk át.
 *
 * A kijelölhető szöveg `[data-start]` attribútumú elemekben van: minden ilyen elem
 * egyetlen szöveg-csomópontot tartalmaz, és a `data-start` az elem első karakterének
 * code-point pozíciója a teljes dokumentumban. Ami nincs ilyen elemben (pl. a
 * „kar. 8–22” címke), az nem számít bele.
 */

const cpLen = (s: string) => Array.from(s).length;

/** Egy kijelölés-határ (csomópont + UTF-16 offset) → code-point pozíció, vagy null */
function pozicio(node: Node, offset: number): number | null {
  // Ha a határ elemre esik (pl. hármas kattintásnál), a szomszédos szöveg-csomópontot vesszük
  if (node.nodeType !== Node.TEXT_NODE) {
    const gyerekek = node.childNodes;
    if (offset < gyerekek.length) {
      const t = elsoSzoveg(gyerekek[offset]);
      return t ? pozicio(t, 0) : null;
    }
    const t = offset > 0 ? utolsoSzoveg(gyerekek[offset - 1]) : null;
    return t ? pozicio(t, t.textContent?.length ?? 0) : null;
  }
  const seg = node.parentElement?.closest<HTMLElement>('[data-start]');
  if (!seg || seg.firstChild !== node) return null;
  const start = Number(seg.dataset.start);
  return start + cpLen((node.textContent ?? '').slice(0, offset));
}

function elsoSzoveg(n: Node): Node | null {
  if (n.nodeType === Node.TEXT_NODE) return n;
  for (const c of Array.from(n.childNodes)) {
    const t = elsoSzoveg(c);
    if (t) return t;
  }
  return null;
}

function utolsoSzoveg(n: Node): Node | null {
  if (n.nodeType === Node.TEXT_NODE) return n;
  for (const c of Array.from(n.childNodes).reverse()) {
    const t = utolsoSzoveg(c);
    if (t) return t;
  }
  return null;
}

/**
 * A `container`-en belüli aktuális kijelölés code-point tartománya, a két szélén levágott
 * szóközökkel. `teljesSzoveg`: a dokumentum teljes szövege (a vágáshoz). null, ha nincs
 * érvényes kijelölés a konténerben.
 */
export function kijeloltTartomany(
  container: HTMLElement,
  teljesSzoveg: string,
): { kezdet: number; veg: number; szoveg: string } | null {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
  const r = sel.getRangeAt(0);
  if (!container.contains(r.startContainer) || !container.contains(r.endContainer)) return null;
  let kezdet = pozicio(r.startContainer, r.startOffset);
  let veg = pozicio(r.endContainer, r.endOffset);
  if (kezdet === null || veg === null || kezdet >= veg) return null;

  const chars = Array.from(teljesSzoveg);
  while (kezdet < veg && /\s/.test(chars[kezdet])) kezdet++;
  while (veg > kezdet && /\s/.test(chars[veg - 1])) veg--;
  if (kezdet >= veg) return null;
  return { kezdet, veg, szoveg: chars.slice(kezdet, veg).join('') };
}

/** Egyetlen buborékon (`[data-turn]`) belüli kijelölés: a turn-sorszám és a kijelölt szöveg */
export function kijeloltMegszolalas(container: HTMLElement): { turn: number; szoveg: string } | null {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
  const r = sel.getRangeAt(0);
  const a = (r.startContainer.nodeType === Node.TEXT_NODE ? r.startContainer.parentElement : (r.startContainer as Element))
    ?.closest<HTMLElement>('[data-turn]');
  const b = (r.endContainer.nodeType === Node.TEXT_NODE ? r.endContainer.parentElement : (r.endContainer as Element))
    ?.closest<HTMLElement>('[data-turn]');
  // Több buborékon átnyúló kijelölésben a beszélő-címkék is benne lennének — azt nem engedjük
  if (!a || a !== b || !container.contains(a)) return null;
  const szoveg = sel.toString().trim();
  return szoveg ? { turn: Number(a.dataset.turn), szoveg } : null;
}
