import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { api } from '../api';
import { readJsonFile } from '../api/importok';
import type { Beszelo, FelvetelAllapot, FelvetelResponse } from '../api/types';
import { kiemel } from '../lib/atiratKiemeles';
import { allapotFelirat } from '../lib/feliratok';
import { formatDatumIdo, formatMp } from '../lib/format';
import { kijeloltMegszolalas } from '../lib/kijeles';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { useIrhato } from '../patient/EllatasKontextus';
import { usePatient } from '../patient/PatientContext';
import { IdezetForm } from './allitas/IdezetForm';
import { csoportApi, csoportok, type FindingCategoryDto } from '../api/felvetelCsoportok';
import { FelvetelCsoportokPanel } from './FelvetelCsoportok';
import { FelvetelOsszefoglaloPanel } from './FelvetelElemzes';
import { Icon, type IconName } from './Icon';
import { ErrorState, Loading, PatientMatch } from './shell';

const beszeloMeta: Record<Beszelo, { label: string; icon: IconName }> = {
  orvos: { label: 'Orvos', icon: 'stetho' },
  beteg: { label: 'Beteg', icon: 'user' },
  egyeb: { label: 'Hozzátartozó / egyéb', icon: 'user' },
};

/**
 * Hangfelvétel-átirat. A beszélő háromértékű: orvos / beteg / egyéb (hozzátartozó) —
 * mindhárom más buborékstílus + felirat. A hozzátartozó kijelentése nem a betegé
 * (anamnézis-hiba lenne összemosni). A kiemelés a turn-sorszámra (backend turnId) megy.
 *
 * A felvétel állapotát frissen kéri le (GET /recordings/{id}), és csak az
 * állapotgép szerint megengedett lépést kínálja fel.
 */
export function TranscriptView({ felvetelId, kiemeltTurnIds }: { felvetelId: number; kiemeltTurnIds: number[] }) {
  const { patient, frissit } = usePatient();
  const felvetelAllapot = useAsync(() => api.getFelvetel(felvetelId), [felvetelId]);
  const { state, reload, refresh } = useAsync(() => api.listMegszolalasok(felvetelId), [felvetelId]);
  const firstRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLOListElement>(null);
  const [kijeloles, setKijeloles] = useState<{ turn: number; szoveg: string } | null>(null);
  const [idezet, setIdezet] = useState<{ turn: number; szoveg: string } | null>(null);
  const irhato = useIrhato();
  const kiemeltKulcs = kiemeltTurnIds.join(',');
  // az összefoglaló hivatkozására kattintva kiemelt megszólalások (az URL-es kiemelés mellett)
  const [ugrott, setUgrott] = useState<number[]>([]);
  const [kwHiba, setKwHiba] = useState<string | null>(null);
  // a kijelölésből csoportba tett elem után a csoport-panel újratölt
  const [csoportFrissites, setCsoportFrissites] = useState(0);
  const ugras = (turnIds: number[]) => {
    setUgrott(turnIds);
    listRef.current
      ?.querySelector(`[data-turn="${turnIds[0]}"]`)
      ?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  };

  // A hangszolgáltatás dolgozik: amíg kész nem lesz, csendben frissítünk
  const automatikusFeldolgozas =
    felvetelAllapot.state.status === 'ok' &&
    felvetelAllapot.state.data.automatikus &&
    felvetelAllapot.state.data.allapot === 'feldolgozas';
  useEffect(() => {
    if (!automatikusFeldolgozas) return;
    const t = window.setInterval(() => {
      void Promise.all([felvetelAllapot.refresh(), refresh()])
        .then(() => frissit())
        .catch(() => undefined);
    }, 3000);
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [automatikusFeldolgozas]);

  useEffect(() => {
    firstRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [state.status, kiemeltKulcs]);

  if (felvetelAllapot.state.status === 'loading') return <Loading what="Felvétel" />;
  if (felvetelAllapot.state.status === 'error') {
    return <ErrorState error={felvetelAllapot.state.error} onRetry={felvetelAllapot.reload} />;
  }
  const felvetel = felvetelAllapot.state.data;
  const sameP = felvetel.patientId === patient.id;
  const beszelok = new Set(state.status === 'ok' ? state.data.map((m) => m.beszelo) : []);

  // Csendes frissítés: a nézet nem ugrik vissza „betöltés…”-re, a lépés üzenete megmarad
  const valtozott = async () => {
    await Promise.all([felvetelAllapot.refresh(), refresh()]);
    await frissit();
  };

  const figyelKijelolest = () => {
    if (idezet || !listRef.current) return;
    setKijeloles(kijeloltMegszolalas(listRef.current));
  };

  return (
    <div className="docview">
      <div className="panel__head">
        <div className="row-baseline">
          <h2 className="h2 h2--sm">Felvétel #{felvetel.id}</h2>
          <span className={`stat stat--${felvetel.allapot}`}>{allapotFelirat[felvetel.allapot]}</span>
          <span className="meta">
            {felvetel.mod === 'surgossegi' ? 'Sürgősségi felvétel' : 'Echokardiográfia diktálás'} ·{' '}
            {formatDatumIdo(felvetel.kezdes)}
            {felvetel.hosszMp > 0 && ` · ${formatMp(felvetel.hosszMp)}`}
            {beszelok.size > 0 && ` · ${beszelok.size} beszélő`}
            {felvetel.ellatasId !== null && ` · ellátás #${felvetel.ellatasId}`}
          </span>
        </div>
        <span className="meta mono">
          {felvetel.hangTorolve ? 'a hang törölve (korábbi felvétel)' : `hang: ${felvetel.hangfajl}`}{' '}
          · ASR: {felvetel.asrModell}
          {felvetel.automatikus && ` · AI-átnézés: ${felvetel.atnezoModell ?? 'nem futott'}`}
        </span>
        {automatikusFeldolgozas && (
          <p className="meta" role="status">
            <span className="stat stat--feldolgozas">Feldolgozás alatt</span> Beszédfelismerés, beszélő-szétválasztás,
            majd MedGemma-átnézés — az átirat magától megjelenik.
          </p>
        )}
        {felvetel.hiba && (
          <p className="form-error" role="alert">
            {felvetel.hiba}
          </p>
        )}
        {felvetel.figyelmeztetesek.length > 0 && (
          <details className="subsection__more">
            <summary>Feldolgozási megjegyzések ({felvetel.figyelmeztetesek.length})</summary>
            <ul className="itemlist">
              {felvetel.figyelmeztetesek.map((w, i) => (
                <li key={i} className="meta">
                  {w}
                </li>
              ))}
            </ul>
          </details>
        )}
        <PatientMatch active={patient} contentPatientId={felvetel.patientId} what="Felvétel" />
        {sameP && !felvetel.automatikus && (
          <AllapotLepesek
            felvetel={felvetel}
            vanAtirat={state.status === 'ok' && state.data.length > 0}
            onValtozott={valtozott}
          />
        )}
        <div className="legend" aria-label="Beszélők jelmagyarázata">
          {(Object.keys(beszeloMeta) as Beszelo[]).map((b) => (
            <span key={b} className="legend__item">
              <span className={`legend__swatch bubble--${b}`} />
              {beszeloMeta[b].label}
            </span>
          ))}
          {felvetel.mod === 'echo_diktalas' && (
            <span className="legend__item">
              <mark className="mark-ertek">52 mm</mark> diktált érték
            </span>
          )}
          <span className="legend__item">
            <span className="badge badge--ai">AI-javított</span> MedGemma módosította (az eredeti megnézhető)
          </span>
        </div>
      </div>
      {state.status === 'loading' && <Loading what="Átirat" />}
      {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
      {state.status === 'ok' && sameP && state.data.length === 0 && !felvetel.automatikus && (
        <p className="empty docview__hint">
          Még nincs átirat. Feldolgozás alatti felvételhez a Python hangmodul kimenete importálható.
        </p>
      )}
      {state.status === 'ok' && sameP && state.data.length > 0 && felvetel.allapot === 'kesz' && (
        <div className="docview__hint">
          <FelvetelOsszefoglaloPanel felvetelId={felvetel.id} onUgras={ugras} />
          <FelvetelCsoportokPanel felvetelId={felvetel.id} frissites={csoportFrissites} onUgras={ugras} />
        </div>
      )}
      {state.status === 'ok' && sameP && state.data.length > 0 && (
        <>
          <ol className="transcript" ref={listRef} onMouseUp={figyelKijelolest} onKeyUp={figyelKijelolest}>
            {state.data
              .slice()
              .sort((a, b) => a.sorszam - b.sorszam)
              .map((m) => {
                const kiemelt = kiemeltTurnIds.includes(m.sorszam) || ugrott.includes(m.sorszam);
                const first =
                  kiemelt && m.sorszam === kiemeltTurnIds.find((t) => state.data.some((x) => x.sorszam === t));
                const meta = beszeloMeta[m.beszelo];
                return (
                  <li key={m.id} className={`turn turn--${m.beszelo}`}>
                    <div ref={first ? firstRef : undefined} className="turn__inner">
                      <span className="turn__who">
                        <Icon name={meta.icon} size={14} />
                        {meta.label}
                        <span className="mono turn__time">
                          #{String(m.sorszam).padStart(2, '0')} · {formatMp(m.kezdetMp)}
                        </span>
                        {kiemelt && <span className="turn__ref">hivatkozott megszólalás</span>}
                        {m.javito && (
                          <span className="badge badge--ai" title={`${m.javito}: ${m.javitasOka ?? ''}`}>
                            AI-javított
                          </span>
                        )}
                      </span>
                      <p
                        data-turn={m.sorszam}
                        className={`bubble bubble--${m.beszelo}${kiemelt ? ' bubble--ref' : ''}`}
                      >
                        {kiemel(m.szoveg, [], felvetel.mod === 'echo_diktalas')}
                      </p>
                      {m.megjegyzes && (
                        <p className="turn__note" role="note">
                          <Icon name="alert" size={13} /> {m.megjegyzes}
                        </p>
                      )}
                      {m.javito && (
                        <details className="turn__orig">
                          <summary>Eredeti (beszédfelismerés)</summary>
                          <p className="meta">
                            {m.eredetiBeszelo && (
                              <>
                                <strong>{beszeloMeta[m.eredetiBeszelo].label}:</strong>{' '}
                              </>
                            )}
                            {m.eredetiSzoveg ?? m.szoveg}
                          </p>
                          {m.javitasOka && <p className="meta">{m.javitasOka}</p>}
                        </details>
                      )}
                    </div>
                  </li>
                );
              })}
          </ol>
          {kijeloles && !idezet && (
            <div className="quotebar" role="status">
              <span className="meta">
                Kijelölve a <span className="mono">#{String(kijeloles.turn).padStart(2, '0')}</span> megszólalásból
              </span>
              <button
                type="button"
                className="btn btn--sm btn--primary"
                onClick={() => setIdezet(kijeloles)}
                disabled={irhato.tiltva}
                title={irhato.title}
              >
                <Icon name="quote" size={14} />
                Idézet állításként…
              </button>
              {felvetel.allapot === 'kesz' && kijeloles.szoveg.trim().length <= 500 && (
                <select
                  className="input input--sm"
                  aria-label="Kijelölés hozzáadása csoporthoz"
                  disabled={irhato.tiltva}
                  title={irhato.title}
                  value=""
                  onChange={(e) =>
                    void csoportApi
                      .hozzaad(felvetel.id, e.target.value as FindingCategoryDto, kijeloles.szoveg, null, [kijeloles.turn])
                      .then(() => {
                        setCsoportFrissites((n) => n + 1);
                        setKwHiba(null);
                        setKijeloles(null);
                      })
                      .catch((err: Error) => setKwHiba(err.message))
                  }
                >
                  <option value="" disabled>
                    Csoportba…
                  </option>
                  {csoportok.map((cs) => (
                    <option key={cs.kategoria} value={cs.kategoria}>
                      {cs.cim}
                    </option>
                  ))}
                </select>
              )}
              <button type="button" className="btn btn--sm" onClick={() => setKijeloles(null)}>
                Mégse
              </button>
              {kwHiba && <span className="form-error">{kwHiba}</span>}
            </div>
          )}
          {idezet && (
            <IdezetForm
              forras={{ tipus: 'megszolalas', felvetelId: felvetel.id, turnIds: [idezet.turn] }}
              szoveg={idezet.szoveg}
              helyLeiras={`Felvétel #${felvetel.id}, megsz. #${String(idezet.turn).padStart(2, '0')}`}
              onDone={() => {
                setIdezet(null);
                setKijeloles(null);
                window.getSelection()?.removeAllRanges();
              }}
              onCancel={() => setIdezet(null)}
            />
          )}
          {!kijeloles && !idezet && (
            <p className="meta docview__hint">
              Jelölj ki szöveget egy megszólaláson belül, hogy szó szerinti idézet-állításként rögzítsd.
            </p>
          )}
        </>
      )}
    </div>
  );
}

/**
 * A felvétel állapotgépe (backend RecordingStatus): RECORDING → PROCESSING | FAILED,
 * PROCESSING → DONE | FAILED. Csak a megengedett lépés jelenik meg; a
 * PROCESSING-hez kötelező a hossz, az átirat csak PROCESSING-ben importálható.
 */
function AllapotLepesek({
  felvetel,
  vanAtirat,
  onValtozott,
}: {
  felvetel: FelvetelResponse;
  vanAtirat: boolean;
  onValtozott: () => Promise<void>;
}) {
  const [hossz, setHossz] = useState('');
  const { busy, error, run } = useAkcio();
  const irhato = useIrhato();
  const [uzenet, setUzenet] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const hosszId = useId();

  const lep = async (allapot: FelvetelAllapot, hosszMp?: number) => {
    setUzenet(null);
    const ok = await run(async () => {
      await api.setFelvetelAllapot(felvetel.id, allapot, hosszMp);
      await onValtozott();
      return true;
    });
    if (ok) setUzenet(`Új állapot: ${allapotFelirat[allapot]}.`);
  };

  async function feldolgozasra(e: FormEvent) {
    e.preventDefault();
    const mp = Number(hossz);
    if (!Number.isInteger(mp) || mp < 0) return;
    await lep('feldolgozas', mp);
  }

  async function importal(fajl: File) {
    setUzenet(null);
    const db = await run(async () => {
      const n = await api.importMegszolalasok(felvetel.id, await readJsonFile(fajl));
      await onValtozott();
      return n;
    });
    if (db !== undefined) setUzenet(`${db} megszólalás importálva.`);
  }

  if (felvetel.allapot === 'kesz' || felvetel.allapot === 'hiba') return null;
  const hosszOk = hossz !== '' && Number.isInteger(Number(hossz)) && Number(hossz) >= 0;

  return (
    <div className="stepbar">
      {felvetel.allapot === 'rogzites' && (
        <form className="row-end" onSubmit={feldolgozasra}>
          <div className="field">
            <label htmlFor={hosszId}>Felvétel hossza (mp)</label>
            <input
              id={hosszId}
              className="input mono input--narrow"
              inputMode="numeric"
              value={hossz}
              onChange={(e) => setHossz(e.target.value.replace(/\D/g, ''))}
            />
          </div>
          <button type="submit" className="btn btn--primary" disabled={irhato.tiltva || busy || !hosszOk} title={irhato.title}>
            Feldolgozásra küldés
          </button>
          <button type="button" className="btn" onClick={() => void lep('hiba')} disabled={irhato.tiltva || busy} title={irhato.title}>
            <Icon name="x" strokeWidth={2.4} />
            Sikertelen
          </button>
        </form>
      )}
      {felvetel.allapot === 'feldolgozas' && (
        <div className="actions">
          {!vanAtirat && (
            <>
              <input
                ref={fileRef}
                type="file"
                accept=".json,application/json"
                className="sr-only"
                aria-label="Átirat JSON-fájlja"
                onChange={(e) => {
                  const f = e.target.files?.[0];
                  e.target.value = '';
                  if (f) void importal(f);
                }}
              />
              <button type="button" className="btn" onClick={() => fileRef.current?.click()} disabled={irhato.tiltva || busy} title={irhato.title}>
                <Icon name="upload" size={16} />
                Átirat importálása (JSON)
              </button>
            </>
          )}
          <button type="button" className="btn btn--primary" onClick={() => void lep('kesz')} disabled={irhato.tiltva || busy} title={irhato.title}>
            <Icon name="check" strokeWidth={2.6} />
            Kész
          </button>
          <button type="button" className="btn" onClick={() => void lep('hiba')} disabled={irhato.tiltva || busy} title={irhato.title}>
            <Icon name="x" strokeWidth={2.4} />
            Sikertelen
          </button>
        </div>
      )}
      {felvetel.allapot === 'feldolgozas' && !vanAtirat && (
        <span className="meta">
          Elfogadott formátum: a Python <span className="mono">beszed_pipeline.py</span> kimenete (
          <span className="mono">chat</span>) vagy a backend <span className="mono">utterances</span> tömbje.
        </span>
      )}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {uzenet && (
        <p className="meta" role="status">
          {uzenet}
        </p>
      )}
    </div>
  );
}
