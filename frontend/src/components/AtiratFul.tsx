import { useEffect, useId, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { useCurrentUser } from '../api/currentUser';
import type { FelvetelResponse } from '../api/types';
import { formatDatumIdo, formatMp } from '../lib/format';
import {
  felvetelInditasa,
  felvetelTamogatott,
  mikrofonElengedese,
  mikrofonMegnyitasa,
  type Felvevo,
} from '../lib/hangfelvetel';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { SzintMero } from './HangprofilPanel';
import { Icon } from './Icon';
import { Segmented } from './Segmented';
import { TranscriptView } from './TranscriptView';

/** Az Átirat fül: új felvétel rögzítése (kérésre vagy ha még nincs) + a kiválasztott felvétel átirata. */
export function AtiratFul({ felvetelId, turnIds }: { felvetelId: number | undefined; turnIds: number[] }) {
  const { felvetelek } = usePatient();
  const [params, setParams] = useSearchParams();
  const ujNyitva = params.get('uj') === 'felvetel' || felvetelek.length === 0;
  const user = useCurrentUser();
  const profil = useAsync(() => (user ? api.getAktivHangprofil(user.id) : Promise.resolve(null)), [user?.id]);
  // Hangprofil nélkül nincs felvétel (a backend is 409-cel tiltja): a szerepkiosztás erre épül
  const vanProfil = profil.state.status === 'ok' && !!profil.state.data?.hasznalhato;

  return (
    <>
      {profil.state.status === 'ok' && !vanProfil ? (
        <div className="tabbar">
          <p className="meta" role="note">
            <Icon name="lock" size={14} /> Hangfelvétel csak hangprofillal indítható: a rendszer a hangmintád alapján
            ismeri fel, melyik megszólalás az orvosé. <Link to="/profil">Hangminta felvétele a Profil oldalon</Link>
          </p>
        </div>
      ) : profil.state.status !== 'ok' ? null : ujNyitva ? (
        <FelvetelForm
          onDone={(id) => setParams({ nezet: 'atirat', felvetel: String(id) })}
          onCancel={felvetelek.length > 0 ? () => setParams({ nezet: 'atirat' }) : undefined}
        />
      ) : (
        <div className="tabbar">
          <button type="button" className="btn btn--sm" onClick={() => setParams({ nezet: 'atirat', uj: 'felvetel' })}>
            <Icon name="mic" size={14} />
            Új felvétel
          </button>
        </div>
      )}
      {felvetelId !== undefined && (
        <TranscriptView key={felvetelId} felvetelId={felvetelId} kiemeltTurnIds={turnIds} />
      )}
    </>
  );
}

/**
 * Új felvétel a böngészőben: a hang a felvétel végén egyben megy a Springnek (POST /recordings,
 * multipart), onnan a Python hangszolgáltatáshoz (docs/voice-module.md). Az orvos a belépett
 * felhasználó, a hangprofilt a backend választja (az aktívat); profil nélkül 409.
 */
function FelvetelForm({ onDone, onCancel }: { onDone: (id: number) => void; onCancel?: () => void }) {
  const { patient, frissit } = usePatient();
  const ellatasok = useAsync(() => api.listEllatasok(patient.id), [patient.id]);
  const [mod, setMod] = useState<FelvetelResponse['mod']>('surgossegi');
  const [ellatasId, setEllatasId] = useState('');
  const [fut, setFut] = useState(false);
  const [hossz, setHossz] = useState(0);
  const [szint, setSzint] = useState(0);
  const [hiba, setHiba] = useState<string | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const felvevoRef = useRef<Felvevo | null>(null);
  const { busy, error, run } = useAkcio();
  const ids = { ellatas: useId() };

  // Oldalváltáskor / Mégse-nél a mikrofon elengedése; a félbehagyott felvétel elvész
  useEffect(
    () => () => {
      felvevoRef.current?.megszakit();
      mikrofonElengedese(streamRef.current);
    },
    [],
  );
  useEffect(() => {
    if (!fut) return;
    const t = window.setInterval(() => {
      setHossz(felvevoRef.current?.hossz() ?? 0);
      setSzint(felvevoRef.current?.szint() ?? 0);
    }, 200);
    return () => window.clearInterval(t);
  }, [fut]);

  // Lezárt ellátáshoz a backend nem enged felvételt: csak a nyitottak választhatók
  const nyitottak = ellatasok.state.status === 'ok' ? ellatasok.state.data.filter((e) => e.veg === null) : [];

  async function indit() {
    setHiba(null);
    try {
      streamRef.current = await mikrofonMegnyitasa();
    } catch (e) {
      setHiba((e as Error).message);
      return;
    }
    felvevoRef.current = felvetelInditasa(streamRef.current);
    setHossz(0);
    setFut(true);
  }

  async function leallitEsFeltolt() {
    const f = felvevoRef.current;
    if (!f) return;
    felvevoRef.current = null;
    const mp = f.hossz();
    const hang = await f.stop();
    setFut(false);
    mikrofonElengedese(streamRef.current);
    streamRef.current = null;
    if (mp < 2) {
      setHiba('Túl rövid felvétel (legalább 2 másodperc kell).');
      return;
    }
    const uj = await run(async () => {
      const r = await api.feltoltFelvetel({
        patientId: patient.id,
        ellatasId: ellatasId ? Number(ellatasId) : null,
        mod,
        hang,
        hosszMp: mp,
      });
      await frissit();
      return r;
    });
    if (uj) onDone(uj.id);
  }

  if (!felvetelTamogatott()) {
    return <p className="form-error">Ez a böngésző nem támogatja a hangfelvételt.</p>;
  }

  return (
    <div className="tabform" aria-label="Új felvétel">
      <div className="row-baseline">
        <h2 className="h2 h2--sm">Új felvétel</h2>
        <span className="meta">a felvétel végén a hang feltöltődik, az átirat a háttérben készül</span>
      </div>
      <div className="formgrid">
        <div className="field">
          <span className="field__legend">Mód</span>
          <Segmented
            label="Felvétel módja"
            value={mod}
            onChange={(v) => !fut && setMod(v)}
            options={[
              ['surgossegi', 'Sürgősségi'],
              ['echo_diktalas', 'Echo diktálás'],
            ]}
          />
        </div>
        <div className="field">
          <label htmlFor={ids.ellatas}>
            Ellátás <span className="meta">(nem kötelező)</span>
          </label>
          <select
            id={ids.ellatas}
            className="input"
            value={ellatasId}
            disabled={fut}
            onChange={(e) => setEllatasId(e.target.value)}
          >
            <option value="">— nincs ellátáshoz kötve —</option>
            {nyitottak.map((e) => (
              <option key={e.id} value={e.id}>
                #{e.id} · {e.tipus.toUpperCase()} · {formatDatumIdo(e.kezdet)}
              </option>
            ))}
          </select>
          {ellatasok.state.status === 'ok' && nyitottak.length === 0 && (
            <span className="meta">Nincs nyitott ellátás (az Ellátások fülön nyitható).</span>
          )}
        </div>
      </div>
      {fut && (
        <div className="recbar" role="status">
          <span className="recbar__dot" aria-hidden="true" />
          <span className="mono">{formatMp(Math.floor(hossz))}</span>
          <SzintMero szint={szint} />
        </div>
      )}
      {(hiba || error) && (
        <p className="form-error" role="alert">
          {hiba ?? error}
        </p>
      )}
      <div className="actions">
        {fut ? (
          <button type="button" className="btn btn--primary" onClick={() => void leallitEsFeltolt()}>
            <Icon name="stop" size={16} />
            Felvétel vége és feltöltés
          </button>
        ) : (
          <button type="button" className="btn btn--primary" onClick={() => void indit()} disabled={busy}>
            <Icon name="mic" size={16} />
            {busy ? 'Feltöltés…' : 'Felvétel indítása'}
          </button>
        )}
        {onCancel && (
          <button type="button" className="btn" onClick={onCancel} disabled={busy || fut}>
            Mégse
          </button>
        )}
      </div>
    </div>
  );
}
