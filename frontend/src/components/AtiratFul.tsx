import { useId, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api';
import type { FelvetelResponse } from '../api/types';
import { formatDatumIdo } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { Icon } from './Icon';
import { Segmented } from './Segmented';
import { TranscriptView } from './TranscriptView';

/** Az Átirat fül: új felvétel rögzítése (kérésre vagy ha még nincs) + a kiválasztott felvétel átirata. */
export function AtiratFul({ felvetelId, turnIds }: { felvetelId: number | undefined; turnIds: number[] }) {
  const { felvetelek } = usePatient();
  const [params, setParams] = useSearchParams();
  const ujNyitva = params.get('uj') === 'felvetel' || felvetelek.length === 0;

  return (
    <>
      {ujNyitva ? (
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
 * POST /api/recordings — a felvétel metaadata. A hangfolyam nem megy át a Springen
 * (voice-module.md): itt csak a már elkészült/folyamatban lévő felvételt tartjuk nyilván.
 * Az orvos a belépett felhasználó, a hangprofilt a backend választja (az aktívat).
 */
function FelvetelForm({ onDone, onCancel }: { onDone: (id: number) => void; onCancel?: () => void }) {
  const { patient, frissit } = usePatient();
  const ellatasok = useAsync(() => api.listEllatasok(patient.id), [patient.id]);
  const [mod, setMod] = useState<FelvetelResponse['mod']>('surgossegi');
  const [ellatasId, setEllatasId] = useState('');
  const [hangfajl, setHangfajl] = useState('');
  const [asr, setAsr] = useState('faster-whisper-large-v3');
  const [tried, setTried] = useState(false);
  const { busy, error, run } = useAkcio();
  const ids = { ellatas: useId(), hangfajl: useId(), asr: useId() };

  // Lezárt ellátáshoz a backend nem enged felvételt: csak a nyitottak választhatók
  const nyitottak = ellatasok.state.status === 'ok' ? ellatasok.state.data.filter((e) => e.veg === null) : [];
  const hibak = {
    hangfajl: !hangfajl.trim() ? 'Add meg a hangfájl útvonalát.' : null,
    asr: !asr.trim() ? 'Add meg az ASR-modellt.' : asr.trim().length > 128 ? 'Legfeljebb 128 karakter.' : null,
  };

  async function submit(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (hibak.hangfajl || hibak.asr) return;
    const f = await run(async () => {
      const uj = await api.createFelvetel({
        patientId: patient.id,
        ellatasId: ellatasId ? Number(ellatasId) : null,
        mod,
        hangfajl,
        asrModell: asr,
      });
      await frissit();
      return uj;
    });
    if (f) onDone(f.id);
  }

  return (
    <form className="tabform" onSubmit={submit} noValidate aria-label="Új felvétel">
      <div className="row-baseline">
        <h2 className="h2 h2--sm">Új felvétel</h2>
        <span className="meta">a felvétel „Rögzítés alatt” állapotban indul</span>
      </div>
      <div className="formgrid">
        <div className="field">
          <span className="field__legend">Mód</span>
          <Segmented
            label="Felvétel módja"
            value={mod}
            onChange={setMod}
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
        <div className="field">
          <label htmlFor={ids.hangfajl}>Hangfájl útvonala</label>
          <input
            id={ids.hangfajl}
            className="input mono"
            placeholder="rec/2026-09-24-sbo-01.wav"
            value={hangfajl}
            onChange={(e) => setHangfajl(e.target.value)}
            aria-invalid={tried && !!hibak.hangfajl}
          />
          {tried && hibak.hangfajl && <span className="form-error">{hibak.hangfajl}</span>}
        </div>
        <div className="field">
          <label htmlFor={ids.asr}>ASR-modell</label>
          <input
            id={ids.asr}
            className="input mono"
            maxLength={128}
            value={asr}
            onChange={(e) => setAsr(e.target.value)}
            aria-invalid={tried && !!hibak.asr}
          />
          {tried && hibak.asr && <span className="form-error">{hibak.asr}</span>}
        </div>
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="actions">
        <button type="submit" className="btn btn--primary" disabled={busy}>
          <Icon name="mic" size={16} />
          {busy ? 'Rögzítés…' : 'Felvétel rögzítése'}
        </button>
        {onCancel && (
          <button type="button" className="btn" onClick={onCancel} disabled={busy}>
            Mégse
          </button>
        )}
      </div>
    </form>
  );
}
