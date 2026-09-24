import { useId, useState, type FormEvent } from 'react';
import { api } from '../api';
import type { ClaimResponse } from '../api/types';
import { parseSzam } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { Icon } from './Icon';
import { ErrorState, Loading } from './shell';

/** Forrásként csak orvosilag elfogadott vagy módosított állítás használható (a backend is ellenőrzi). */
const jovahagyott = (a: ClaimResponse) =>
  a.jovahagyas?.dontes === 'jovahagyva' || a.jovahagyas?.dontes === 'modositva';

const rovid = (s: string, n = 70) => (s.length > n ? `${s.slice(0, n)}…` : s);
const allitasSzoveg = (a: ClaimResponse) => a.ervenyesSzoveg || a.idezet || a.cim;

/** Gyógyszerek és allergiák — a klinikai tényként megjelenő listák. */
export function GyogyszerAllergiaPanel() {
  return (
    <div className="tabbody">
      <AllergiaResz />
      <GyogyszerResz />
    </div>
  );
}

/** Forrás-állítás választó + a forrás aktuális állapota */
function ForrasValaszto({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const { allitasok } = usePatient();
  const id = useId();
  const lehetseges = allitasok.filter(jovahagyott);
  return (
    <div className="field grow">
      <label htmlFor={id}>
        Forrás-állítás <span className="meta">(nem kötelező — csak jóváhagyott)</span>
      </label>
      <select id={id} className="input" value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">— kézi rögzítés, forrás nélkül —</option>
        {lehetseges.map((a) => (
          <option key={a.id} value={a.id}>
            #{a.id} · {rovid(allitasSzoveg(a))}
          </option>
        ))}
      </select>
    </div>
  );
}

/**
 * A forrás-állítás jelzése. Ha azóta elutasították, figyelmeztet: a belőle
 * rögzített tétel ettől még a listán marad (a backend nem vonja vissza) —
 * az orvos döntse el, leállítja-e.
 */
function ForrasJelzes({ allitasId }: { allitasId: number | null }) {
  const { allitasok } = usePatient();
  if (allitasId === null) return <span className="meta">kézi rögzítés</span>;
  const a = allitasok.find((x) => x.id === allitasId);
  if (!a) return <span className="meta">forrás: állítás #{allitasId}</span>;
  if (a.jovahagyas?.dontes === 'elutasitva') {
    return (
      <span className="warnchip" title="A tétel forrás-állítását a rögzítés után elutasították">
        <Icon name="alert" size={14} />
        forrás-állítás #{a.id} azóta elutasítva
      </span>
    );
  }
  return (
    <span className="meta" title={allitasSzoveg(a)}>
      forrás: #{a.id} · {rovid(allitasSzoveg(a), 50)}
    </span>
  );
}

// ======================= allergia =======================

function AllergiaResz() {
  const { patient } = usePatient();
  const { state, reload, refresh } = useAsync(() => api.listAllergiak(patient.id), [patient.id]);
  const [anyag, setAnyag] = useState('');
  const [forras, setForras] = useState('');
  const [reszlet, setReszlet] = useState<number | null>(null);
  const mentes = useAkcio();
  const anyagId = useId();

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!anyag.trim()) return;
    const a = await mentes.run(() =>
      api.createAllergia({ patientId: patient.id, anyag, forrasAllitasId: forras ? Number(forras) : null }),
    );
    if (a) {
      setAnyag('');
      setForras('');
      await refresh();
    }
  }

  return (
    <section className="subsection" aria-label="Allergiák">
      <h2 className="h2 h2--sm">Allergiák</h2>
      {state.status === 'loading' && <Loading what="Allergiák" />}
      {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
      {state.status === 'ok' && state.data.some((a) => a.patientId !== patient.id) && (
        <ErrorState error={new Error('Az allergialista nem az aktív beteghez tartozik — rejtve.')} />
      )}
      {state.status === 'ok' && state.data.every((a) => a.patientId === patient.id) && (
        <>
          {state.data.length === 0 && <p className="meta">Nincs rögzített allergia.</p>}
          <ul className="itemlist">
            {state.data.map((a) => (
              <li key={a.id} className="item item--critical">
                <div className="item__row">
                  <Icon name="alert" size={16} strokeWidth={2.4} />
                  <strong>{a.anyag}</strong>
                  <ForrasJelzes allitasId={a.forrasAllitasId} />
                  <button
                    type="button"
                    className="btn btn--sm push-right"
                    aria-expanded={reszlet === a.id}
                    onClick={() => setReszlet((r) => (r === a.id ? null : a.id))}
                  >
                    Részletek
                  </button>
                </div>
                {reszlet === a.id && <AllergiaReszlet id={a.id} />}
              </li>
            ))}
          </ul>
        </>
      )}
      <form className="row-end" onSubmit={submit} aria-label="Allergia rögzítése">
        <div className="field grow">
          <label htmlFor={anyagId}>Anyag</label>
          <input
            id={anyagId}
            className="input"
            maxLength={255}
            placeholder="pl. Penicillin"
            value={anyag}
            onChange={(e) => setAnyag(e.target.value)}
          />
        </div>
        <ForrasValaszto value={forras} onChange={setForras} />
        <button type="submit" className="btn btn--primary" disabled={mentes.busy || !anyag.trim()}>
          <Icon name="plus" size={16} strokeWidth={2.4} />
          Allergia rögzítése
        </button>
      </form>
      {mentes.error && (
        <p className="form-error" role="alert">
          {mentes.error}
        </p>
      )}
    </section>
  );
}

/** GET /api/allergies/{id} */
function AllergiaReszlet({ id }: { id: number }) {
  const { patient } = usePatient();
  const { state, reload } = useAsync(() => api.getAllergia(id), [id]);
  if (state.status === 'loading') return <Loading what="Allergia" />;
  if (state.status === 'error') return <ErrorState error={state.error} onRetry={reload} />;
  if (state.data.patientId !== patient.id) return <ErrorState error={new Error('Nem az aktív beteg allergiája.')} />;
  return (
    <dl className="deflist">
      <dt>Azonosító</dt>
      <dd className="mono">#{state.data.id}</dd>
      <dt>Anyag</dt>
      <dd>{state.data.anyag}</dd>
      <dt>Forrás</dt>
      <dd>
        <ForrasJelzes allitasId={state.data.forrasAllitasId} />
      </dd>
    </dl>
  );
}

// ======================= gyógyszer =======================

function GyogyszerResz() {
  const { patient } = usePatient();
  const [csakAktiv, setCsakAktiv] = useState(true);
  const { state, reload, refresh } = useAsync(
    () => api.listGyogyszerek(patient.id, csakAktiv),
    [patient.id, csakAktiv],
  );
  const [reszlet, setReszlet] = useState<number | null>(null);
  const leallitas = useAkcio();

  async function leallit(id: number) {
    const g = await leallitas.run(() => api.leallitGyogyszer(id));
    if (g) await refresh();
  }

  return (
    <section className="subsection" aria-label="Gyógyszerek">
      <div className="row-baseline">
        <h2 className="h2 h2--sm">Gyógyszerek</h2>
        <label className="checkrow push-right">
          <input type="checkbox" checked={csakAktiv} onChange={(e) => setCsakAktiv(e.target.checked)} />
          Csak a jelenleg szedettek
        </label>
      </div>
      {state.status === 'loading' && <Loading what="Gyógyszerek" />}
      {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
      {state.status === 'ok' && state.data.some((g) => g.patientId !== patient.id) && (
        <ErrorState error={new Error('A gyógyszerlista nem az aktív beteghez tartozik — rejtve.')} />
      )}
      {state.status === 'ok' && state.data.every((g) => g.patientId === patient.id) && (
        <>
          {state.data.length === 0 && (
            <p className="meta">{csakAktiv ? 'Nincs jelenleg szedett gyógyszer.' : 'Nincs rögzített gyógyszer.'}</p>
          )}
          <ul className="itemlist">
            {state.data.map((g) => (
              <li key={g.id} className={`item${g.aktiv ? '' : ' item--off'}`}>
                <div className="item__row">
                  <Icon name="pill" size={16} />
                  <strong>{g.nev}</strong>
                  <span className="mono">
                    {g.dozis !== null && `${String(g.dozis).replace('.', ',')} ${g.dozisEgyseg ?? ''}`}
                  </span>
                  <span className="meta">{[g.hatoanyag, g.alkalmazas].filter(Boolean).join(' · ')}</span>
                  {!g.aktiv && <span className="stat stat--kesz">Leállítva</span>}
                  <div className="actions push-right">
                    <button
                      type="button"
                      className="btn btn--sm"
                      aria-expanded={reszlet === g.id}
                      onClick={() => setReszlet((r) => (r === g.id ? null : g.id))}
                    >
                      Részletek
                    </button>
                    {g.aktiv && (
                      <button
                        type="button"
                        className="btn btn--sm"
                        onClick={() => void leallit(g.id)}
                        disabled={leallitas.busy}
                      >
                        <Icon name="stop" size={14} />
                        Leállítás
                      </button>
                    )}
                  </div>
                </div>
                <div className="item__sub">
                  <ForrasJelzes allitasId={g.forrasAllitasId} />
                </div>
                {reszlet === g.id && <GyogyszerReszlet id={g.id} />}
              </li>
            ))}
          </ul>
        </>
      )}
      {leallitas.error && (
        <p className="form-error" role="alert">
          {leallitas.error}
        </p>
      )}
      <GyogyszerForm onSaved={refresh} />
    </section>
  );
}

/** GET /api/medications/{id} */
function GyogyszerReszlet({ id }: { id: number }) {
  const { patient } = usePatient();
  const { state, reload } = useAsync(() => api.getGyogyszer(id), [id]);
  if (state.status === 'loading') return <Loading what="Gyógyszer" />;
  if (state.status === 'error') return <ErrorState error={state.error} onRetry={reload} />;
  const g = state.data;
  if (g.patientId !== patient.id) return <ErrorState error={new Error('Nem az aktív beteg gyógyszere.')} />;
  return (
    <dl className="deflist">
      <dt>Azonosító</dt>
      <dd className="mono">#{g.id}</dd>
      <dt>Név</dt>
      <dd>{g.nev}</dd>
      <dt>Hatóanyag</dt>
      <dd>{g.hatoanyag ?? '—'}</dd>
      <dt>Dózis</dt>
      <dd className="mono">{g.dozis !== null ? `${String(g.dozis).replace('.', ',')} ${g.dozisEgyseg ?? ''}` : '—'}</dd>
      <dt>Alkalmazás</dt>
      <dd>{g.alkalmazas ?? '—'}</dd>
      <dt>Állapot</dt>
      <dd>{g.aktiv ? 'szedett' : 'leállítva'}</dd>
      <dt>Forrás</dt>
      <dd>
        <ForrasJelzes allitasId={g.forrasAllitasId} />
      </dd>
    </dl>
  );
}

/** POST /api/medications — dózishoz kötelező a mértékegység; legfeljebb 3 tizedes (NUMERIC(10,3)) */
function GyogyszerForm({ onSaved }: { onSaved: () => Promise<void> }) {
  const { patient } = usePatient();
  const [nev, setNev] = useState('');
  const [hatoanyag, setHatoanyag] = useState('');
  const [dozis, setDozis] = useState('');
  const [egyseg, setEgyseg] = useState('');
  const [alkalmazas, setAlkalmazas] = useState('');
  const [forras, setForras] = useState('');
  const [tried, setTried] = useState(false);
  const { busy, error, run } = useAkcio();
  const ids = { nev: useId(), hat: useId(), doz: useId(), egy: useId(), alk: useId() };

  const dozisSzam = dozis.trim() ? parseSzam(dozis, 3) : null;
  const hiba = !nev.trim()
    ? 'Add meg a gyógyszer nevét.'
    : dozis.trim() && (dozisSzam === null || dozisSzam <= 0 || dozisSzam >= 1e7)
      ? 'A dózis pozitív szám legyen, legfeljebb 3 tizedessel.'
      : dozisSzam !== null && !egyseg.trim()
        ? 'Dózishoz add meg a mértékegységet (pl. mg).'
        : null;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (hiba) return;
    const g = await run(() =>
      api.createGyogyszer({
        patientId: patient.id,
        nev,
        hatoanyag: hatoanyag || null,
        dozis: dozisSzam,
        dozisEgyseg: egyseg || null,
        alkalmazas: alkalmazas || null,
        forrasAllitasId: forras ? Number(forras) : null,
      }),
    );
    if (g) {
      setNev('');
      setHatoanyag('');
      setDozis('');
      setEgyseg('');
      setAlkalmazas('');
      setForras('');
      setTried(false);
      await onSaved();
    }
  }

  return (
    <form className="tabform tabform--inset" onSubmit={submit} noValidate aria-label="Gyógyszer rögzítése">
      <strong>Gyógyszer rögzítése</strong>
      <div className="formgrid">
        <div className="field">
          <label htmlFor={ids.nev}>Név</label>
          <input id={ids.nev} className="input" maxLength={255} value={nev} onChange={(e) => setNev(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor={ids.hat}>Hatóanyag</label>
          <input
            id={ids.hat}
            className="input"
            maxLength={255}
            value={hatoanyag}
            onChange={(e) => setHatoanyag(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor={ids.doz}>Dózis</label>
          <input
            id={ids.doz}
            className="input mono"
            inputMode="decimal"
            value={dozis}
            onChange={(e) => setDozis(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor={ids.egy}>Egység</label>
          <input
            id={ids.egy}
            className="input"
            maxLength={255}
            placeholder="mg"
            value={egyseg}
            onChange={(e) => setEgyseg(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor={ids.alk}>Alkalmazás</label>
          <input
            id={ids.alk}
            className="input"
            maxLength={255}
            placeholder="per os"
            value={alkalmazas}
            onChange={(e) => setAlkalmazas(e.target.value)}
          />
        </div>
        <ForrasValaszto value={forras} onChange={setForras} />
      </div>
      {tried && hiba && <p className="form-error">{hiba}</p>}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="actions">
        <button type="submit" className="btn btn--primary" disabled={busy}>
          <Icon name="plus" size={16} strokeWidth={2.4} />
          {busy ? 'Mentés…' : 'Gyógyszer rögzítése'}
        </button>
      </div>
    </form>
  );
}
