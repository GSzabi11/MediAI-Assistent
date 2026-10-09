import { useCallback, useEffect, useRef, useState } from 'react';

export type AsyncState<T> = { status: 'loading' } | { status: 'error'; error: Error } | { status: 'ok'; data: T };

/**
 * Egyszerű adatbetöltő hook. A `deps` változásakor újratölt, és eldobja a
 * régi kérés eredményét — így betegváltáskor nem jelenhet meg az előző beteg
 * adata az új fejléc alatt.
 *
 *  - reload():  teljes újratöltés, „betöltés…” állapottal
 *  - refresh(): csendes frissítés (pl. mentés után) — a régi adat látszik, amíg az új
 *               meg nem jön, így a nyitott űrlapok és a görgetés nem vesznek el.
 *               Hibánál a hívó kapja a kivételt, a képernyőn maradó adat nem tűnik el.
 */
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [state, setState] = useState<AsyncState<T>>({ status: 'loading' });
  const [tick, setTick] = useState(0);
  const fnRef = useRef(fn);
  // Minden futás egy „generáció”: ha közben a deps változott, a késő válasz eldobódik
  const gen = useRef(0);

  useEffect(() => {
    fnRef.current = fn;
  });

  useEffect(() => {
    const my = ++gen.current;
    setState({ status: 'loading' });
    fn().then(
      (data) => gen.current === my && setState({ status: 'ok', data }),
      (error: Error) => gen.current === my && setState({ status: 'error', error }),
    );
    return () => {
      gen.current++;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  const refresh = useCallback(async () => {
    const my = gen.current;
    const data = await fnRef.current();
    if (gen.current === my) setState({ status: 'ok', data });
  }, []);
  const setData = useCallback((updater: (prev: T) => T) => {
    setState((s) => (s.status === 'ok' ? { status: 'ok', data: updater(s.data) } : s));
  }, []);

  return { state, reload, refresh, setData };
}
