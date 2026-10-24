import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api';
import { useCurrentUser } from '../api/currentUser';
import { ApiError, type FelvetelResponse } from '../api/types';
import { formatMp } from '../lib/format';
import {
  felvetelInditasa,
  felvetelTamogatott,
  mikrofonElengedese,
  mikrofonMegnyitasa,
  type Felvevo,
} from '../lib/hangfelvetel';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { useEllatasKontextus } from '../patient/EllatasKontextus';
import { usePatient } from '../patient/PatientContext';
import { ellatasRovid } from './EllatasValaszto';
import { SzintMero } from './HangprofilPanel';
import { Icon } from './Icon';
import { TranscriptView } from './TranscriptView';

/** Az Átirat fül: új felvétel rögzítése (kérésre vagy ha még nincs) + a kiválasztott felvétel átirata. */
export function AtiratFul({ felvetelId, turnIds }: { felvetelId: number | undefined; turnIds: number[] }) {
  const { felvetelek } = usePatient();
  const { csakOlvas } = useEllatasKontextus();
  const [params, setParams] = useSearchParams();
  // Informálódás módban felvétel nem indítható (docs/care-context.md)
  const ujNyitva = !csakOlvas && (params.get('uj') === 'felvetel' || felvetelek.length === 0);
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
          <button
            type="button"
            className="btn btn--sm"
            onClick={() => setParams({ nezet: 'atirat', uj: 'felvetel' })}
            disabled={csakOlvas}
            title={csakOlvas ? 'Informálódás módban nem elérhető' : undefined}
          >
            <Icon name="mic" size={14} />
            Új felvétel
          </button>
        </div>
      )}
      {felvetelId !== undefined && <TranscriptView key={felvetelId} felvetelId={felvetelId} kiemeltTurnIds={turnIds} />}
    </>
  );
}

/**
 * Új felvétel a böngészőben: a hang a felvétel végén egyben megy a Springnek (POST /recordings,
 * multipart), onnan a Python hangszolgáltatáshoz (docs/voice-module.md). Az orvos a belépett
 * felhasználó, a hangprofilt a backend választja (az aktívat); profil nélkül 409.
 * Az ellátás az aktív ellátás-kontextus (docs/care-context.md) — nincs külön választás.
 */
function FelvetelForm({ onDone, onCancel }: { onDone: (id: number) => void; onCancel?: () => void }) {
  const { patient, frissit } = usePatient();
  const { kontextus, lezarult } = useEllatasKontextus();
  const ellatas = kontextus.mod === 'ellatas' ? kontextus.ellatas : null;
  // a mód az ellátás típusából jön: echo-ellátás → echo diktálás, SBO / ambuláns → orvos–beteg beszélgetés
  const mod: FelvetelResponse['mod'] = ellatas?.tipus === 'echo' ? 'echo_diktalas' : 'surgossegi';
  const [fut, setFut] = useState(false);
  const [hossz, setHossz] = useState(0);
  const [szint, setSzint] = useState(0);
  const [hiba, setHiba] = useState<string | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const felvevoRef = useRef<Felvevo | null>(null);
  const { busy, error, run } = useAkcio();

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
    if (!ellatas) return;
    const uj = await run(async () => {
      try {
        const r = await api.feltoltFelvetel({ patientId: patient.id, ellatasId: ellatas.id, mod, hang, hosszMp: mp });
        await frissit();
        return r;
      } catch (e) {
        // 409 lehet a lezárt ellátás (vagy a hiányzó hangprofil): ha az ellátás lezárult, vissza a választóhoz
        if (e instanceof ApiError && e.status === 409) {
          const friss = await api.getEllatas(ellatas.id).catch(() => null);
          if (friss && friss.veg !== null) lezarult();
        }
        throw e;
      }
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
      <p className="meta">
        Mód: {mod === 'echo_diktalas' ? 'Echo diktálás (csak az orvos hangja)' : 'Orvos–beteg beszélgetés'} — az
        ellátás típusából
      </p>
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
            Felvétel vége és feltöltés{ellatas && ` — ${ellatasRovid[ellatas.tipus]}-ellátásba`}
          </button>
        ) : (
          <button type="button" className="btn btn--primary" onClick={() => void indit()} disabled={busy}>
            <Icon name="mic" size={16} />
            {busy ? 'Feltöltés…' : 'Felvétel indítása'}
            {!busy && ellatas && ` · Mentés: ${ellatasRovid[ellatas.tipus]}-ellátásba (#${ellatas.id})`}
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
