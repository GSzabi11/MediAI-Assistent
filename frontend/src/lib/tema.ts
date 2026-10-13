import { useSyncExternalStore } from 'react';

/**
 * Megjelenés: 'system' a rendszer beállítását követi (alapértelmezett), 'light' / 'dark' felülírja.
 * A választás böngészőnként a localStorage-ban él; a tényleges témát a <html data-theme>
 * attribútum hordozza (tokens.css). Az index.html beágyazott szkriptje ugyanezt teszi az első
 * kirajzolás előtt — a kulcsot és a logikát ott is egyezően kell tartani.
 */
export type TemaMod = 'system' | 'light' | 'dark';

const KULCS = 'mediai.theme';
const sotetMedia = window.matchMedia('(prefers-color-scheme: dark)');
const figyelok = new Set<() => void>();

function olvas(): TemaMod {
  try {
    const v = localStorage.getItem(KULCS);
    return v === 'light' || v === 'dark' ? v : 'system';
  } catch {
    return 'system';
  }
}

let aktualis: TemaMod = olvas();

function alkalmaz() {
  const tema = aktualis === 'system' ? (sotetMedia.matches ? 'dark' : 'light') : aktualis;
  document.documentElement.dataset.theme = tema;
}

export function setTemaMod(mod: TemaMod) {
  aktualis = mod;
  try {
    if (mod === 'system') localStorage.removeItem(KULCS);
    else localStorage.setItem(KULCS, mod);
  } catch {
    // privát mód / tiltott tárhely: a választás csak ebben a lapban él
  }
  alkalmaz();
  figyelok.forEach((f) => f());
}

export function useTemaMod(): TemaMod {
  return useSyncExternalStore(
    (f) => {
      figyelok.add(f);
      return () => figyelok.delete(f);
    },
    () => aktualis,
  );
}

/** Egyszer, induláskor: beállítja a témát, és rendszer módban követi a rendszer váltását. */
export function initTema() {
  alkalmaz();
  sotetMedia.addEventListener('change', () => {
    if (aktualis === 'system') alkalmaz();
  });
  // másik lapon váltott téma
  window.addEventListener('storage', (e) => {
    if (e.key !== KULCS) return;
    aktualis = olvas();
    alkalmaz();
    figyelok.forEach((f) => f());
  });
}
