import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { setCsakOlvasas } from '../api/http';
import type { Ellatas, EllatasTipus } from '../api/types';
import { useAsync } from '../lib/useAsync';

/**
 * Ellátás-kontextus (docs/care-context.md): a beteg kiválasztása után az orvos egy nyitott ellátásban
 * dolgozik, vagy Informálódás módban csak olvas. A választás egyetlen helyen él (ez a modul), és az
 * URL-ben is (`?ellatas=ID` / `?mod=informalodas`), hogy frissítés és új lap után is megmaradjon.
 */
export type Kontextus = { mod: 'ellatas'; ellatas: Ellatas } | { mod: 'informalodas' };

/** A blokkolt (író) gombok tooltipje Informálódás módban */
export const CSAK_OLVASAS_TIPP = 'Informálódás módban nem elérhető';

export interface EllatasKontextusCtx {
  kontextus: Kontextus;
  csakOlvas: boolean;
  /** Vissza az ellátás-választóhoz (pl. a fejléc chipjéről, vagy „Megnyitás ellátásban”) */
  valtas: () => void;
  /** Az aktív ellátás közben lezárult (a felületen vagy máshol — 409): vissza a választóhoz, üzenettel */
  lezarult: () => void;
}

export const EllatasKontextus = createContext<EllatasKontextusCtx | null>(null);

export function useEllatasKontextus(): EllatasKontextusCtx {
  const ctx = useContext(EllatasKontextus);
  if (!ctx) throw new Error('useEllatasKontextus: nincs ellátás-kontextus');
  return ctx;
}

/**
 * Író gombokhoz: Informálódás módban `disabled` + tooltip. A gomb látható marad.
 * Használat: `<button {...irhato.gomb} disabled={irhato.tiltva || busy}>`.
 */
export function useIrhato() {
  const ctx = useContext(EllatasKontextus);
  const tiltva = !!ctx?.csakOlvas;
  return { tiltva, title: tiltva ? CSAK_OLVASAS_TIPP : undefined };
}

type Valasztas = { ellatasId: number } | 'informalodas';

const UTOLSO_KEY = 'mediai.ellatas.utolso';
const TIPUS_KEY = 'mediai.ellatas.utolsoTipus';

/** Betegenként az utoljára használt ellátás (előválasztáshoz) — kényelmi, böngészőnként */
export function utolsoEllatas(patientId: number): number | null {
  try {
    const m = JSON.parse(localStorage.getItem(UTOLSO_KEY) ?? '{}') as Record<string, number>;
    return typeof m[patientId] === 'number' ? m[patientId] : null;
  } catch {
    return null;
  }
}

function utolsoMentese(patientId: number, ellatasId: number) {
  try {
    const m = JSON.parse(localStorage.getItem(UTOLSO_KEY) ?? '{}') as Record<string, number>;
    m[patientId] = ellatasId;
    localStorage.setItem(UTOLSO_KEY, JSON.stringify(m));
  } catch {
    /* privát mód: nem baj */
  }
}

export function utolsoTipus(): EllatasTipus {
  try {
    const t = localStorage.getItem(TIPUS_KEY);
    return t === 'ambulans' || t === 'echo' ? t : 'sbo';
  } catch {
    return 'sbo';
  }
}

function tipusMentese(t: EllatasTipus) {
  try {
    localStorage.setItem(TIPUS_KEY, t);
  } catch {
    /* privát mód */
  }
}

function urlbol(params: URLSearchParams): Valasztas | null {
  if (params.get('mod') === 'informalodas') return 'informalodas';
  const id = Number(params.get('ellatas'));
  return Number.isInteger(id) && id > 0 ? { ellatasId: id } : null;
}

const azonos = (a: Valasztas | null, b: Valasztas | null) =>
  a === b || (typeof a === 'object' && typeof b === 'object' && a?.ellatasId === b?.ellatasId);

/**
 * A betegoldal ellátás-kapuja. Az URL és a React-állapot együtt: a betegoldalon belüli linkek nagy része
 * csak `?nezet=…`-t ad meg, ezért ha a paraméter kiesik, az állapotból visszaírjuk (replace).
 */
export function useEllatasKapu(patientId: number) {
  const [params, setParams] = useSearchParams();
  const ellatasok = useAsync(() => api.listEllatasok(patientId), [patientId]);
  const urlValasztas = urlbol(params);
  const [valasztas, setValasztas] = useState<Valasztas | null>(urlValasztas);
  const [uzenet, setUzenet] = useState<string | null>(null);

  const urlbaIr = useCallback(
    (v: Valasztas | null) =>
      setParams(
        (p) => {
          const n = new URLSearchParams(p);
          n.delete('ellatas');
          n.delete('mod');
          if (v === 'informalodas') n.set('mod', 'informalodas');
          else if (v) n.set('ellatas', String(v.ellatasId));
          return n;
        },
        { replace: true },
      ),
    [setParams],
  );

  // Az URL VÁLTOZÁSA nyer (pl. kézzel átírt cím); ha a paraméter kiesett a linkből, visszaírjuk.
  // Csak a változást vesszük át, hogy a saját (még be nem érkezett) URL-írásunkat ne írja felül a régi.
  const elozoUrl = useRef(urlValasztas);
  useEffect(() => {
    const valtozott = !azonos(elozoUrl.current, urlValasztas);
    elozoUrl.current = urlValasztas;
    if (urlValasztas) {
      if (valtozott && !azonos(urlValasztas, valasztas)) setValasztas(urlValasztas);
    } else if (valasztas) urlbaIr(valasztas);
  }, [urlValasztas, valasztas, urlbaIr]);

  const lista = ellatasok.state.status === 'ok' ? ellatasok.state.data : null;
  const aktivId = valasztas && valasztas !== 'informalodas' ? valasztas.ellatasId : null;
  const aktiv = aktivId !== null ? lista?.find((e) => e.id === aktivId && e.patientId === patientId) : undefined;

  // Csak a beteg NYITOTT ellátása lehet aktív: lezárt / ismeretlen / más betegé → vissza a választóhoz
  useEffect(() => {
    if (aktivId === null || !lista) return;
    if (aktiv && aktiv.veg === null) return;
    setUzenet(
      aktiv
        ? `A #${aktivId} ellátás közben lezárult — válassz másikat, vagy nyiss újat.`
        : `A #${aktivId} ellátás nem ennek a betegnek a nyitott ellátása.`,
    );
    setValasztas(null);
    urlbaIr(null);
  }, [aktivId, aktiv, lista, urlbaIr]);

  const kontextus: Kontextus | null =
    valasztas === 'informalodas'
      ? { mod: 'informalodas' }
      : aktiv && aktiv.veg === null
        ? { mod: 'ellatas', ellatas: aktiv }
        : null;
  const csakOlvas = kontextus?.mod === 'informalodas';

  // A HTTP-réteg fejléce (X-Access-Mode) — a betegoldal elhagyásakor kikapcsol
  useLayoutEffect(() => {
    setCsakOlvasas(csakOlvas);
    return () => setCsakOlvasas(false);
  }, [csakOlvas]);

  const valaszt = useCallback(
    (v: Valasztas) => {
      if (v !== 'informalodas') utolsoMentese(patientId, v.ellatasId);
      setUzenet(null);
      setValasztas(v);
      urlbaIr(v);
    },
    [patientId, urlbaIr],
  );

  const { refresh } = ellatasok;
  const valtas = useCallback(() => {
    setUzenet(null);
    setValasztas(null);
    urlbaIr(null);
    refresh().catch(() => {
      /* a választó a régi listát mutatja */
    });
  }, [urlbaIr, refresh]);

  // Lezárás után friss lista: a fenti ellenőrzés a lezárt ellátást észreveszi és üzenettel visszavisz
  const lezarult = useCallback(() => {
    refresh().catch(() => {
      setUzenet('Az ellátás lezárult — válassz másikat, vagy nyiss újat.');
      setValasztas(null);
      urlbaIr(null);
    });
  }, [refresh, urlbaIr]);

  /** Új ellátás a választóból, egy kattintással; utána rögtön aktív */
  const ujEllatas = useCallback(
    async (tipus: EllatasTipus) => {
      const e = await api.createEllatas(patientId, tipus);
      tipusMentese(tipus);
      await refresh();
      valaszt({ ellatasId: e.id });
    },
    [patientId, refresh, valaszt],
  );

  const ctx: EllatasKontextusCtx | null = kontextus ? { kontextus, csakOlvas, valtas, lezarult } : null;
  return { ctx, ellatasok, valaszt, ujEllatas, uzenet, varakozik: aktivId !== null && !lista };
}

export type EllatasKapu = ReturnType<typeof useEllatasKapu>;
