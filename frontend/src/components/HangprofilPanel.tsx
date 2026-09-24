import { useId, useState, type FormEvent } from 'react';
import { api } from '../api';
import type { Hangprofil } from '../api/types';
import { formatDatumIdo, helyiToIso, mostHelyi, parseSzam } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { Icon } from './Icon';
import { ErrorState, Loading } from './shell';

/**
 * Hangprofil: GET …/active, GET …/history, POST /voice-profiles. Felhasználónként
 * legfeljebb egy aktív profil (részleges egyedi index); új profilnál a régi
 * automatikusan inaktív lesz, de az előzményben megmarad. Az embeddinget (a
 * hanglenyomatot) a Python hangmodul kezeli — itt csak a metaadat.
 */
export function HangprofilPanel({ felhasznaloId, szerkesztheto }: { felhasznaloId: number; szerkesztheto: boolean }) {
  const aktiv = useAsync(() => api.getAktivHangprofil(felhasznaloId), [felhasznaloId]);
  const elozmeny = useAsync(() => api.listHangprofilok(felhasznaloId), [felhasznaloId]);
  const [ujNyitva, setUjNyitva] = useState(false);

  const mentve = async () => {
    setUjNyitva(false);
    await Promise.all([aktiv.refresh(), elozmeny.refresh()]);
  };

  return (
    <section className="subsection" aria-label="Hangprofil">
      <div className="row-baseline">
        <h2 className="h2 h2--sm">Hangprofil</h2>
        <span className="meta">a beszélő-azonosításhoz (orvos hangmintája)</span>
        {szerkesztheto && !ujNyitva && (
          <button type="button" className="btn btn--sm push-right" onClick={() => setUjNyitva(true)}>
            <Icon name="mic" size={14} />
            Új hangprofil
          </button>
        )}
      </div>

      {ujNyitva && <HangprofilForm felhasznaloId={felhasznaloId} onDone={mentve} onCancel={() => setUjNyitva(false)} />}

      {aktiv.state.status === 'loading' && <Loading what="Aktív hangprofil" />}
      {aktiv.state.status === 'error' && <ErrorState error={aktiv.state.error} onRetry={aktiv.reload} />}
      {aktiv.state.status === 'ok' &&
        (aktiv.state.data ? (
          <ProfilAdatok p={aktiv.state.data} />
        ) : (
          <p className="meta">Nincs aktív hangprofil — a felvételek beszélő-azonosítás nélkül készülnek.</p>
        ))}

      {elozmeny.state.status === 'ok' && elozmeny.state.data.length > 1 && (
        <details className="subsection__more">
          <summary>Korábbi profilok ({elozmeny.state.data.length - 1})</summary>
          <ul className="itemlist">
            {elozmeny.state.data
              .filter((p) => !p.aktiv)
              .map((p) => (
                <li key={p.id} className="item item--off">
                  <div className="item__row">
                    <span className="stat stat--kesz">Inaktív</span>
                    <span className="mono">{p.wavUt}</span>
                    <span className="meta">
                      rögzítve {formatDatumIdo(p.rogzitve)} · {p.embeddingModell}
                    </span>
                  </div>
                </li>
              ))}
          </ul>
        </details>
      )}
      {elozmeny.state.status === 'error' && <ErrorState error={elozmeny.state.error} onRetry={elozmeny.reload} />}
    </section>
  );
}

function ProfilAdatok({ p }: { p: Hangprofil }) {
  return (
    <dl className="deflist">
      <dt>Állapot</dt>
      <dd>
        <span className="stat stat--nyitott">Aktív</span>
      </dd>
      <dt>Hangminta</dt>
      <dd className="mono">{p.wavUt}</dd>
      <dt>Beágyazó modell</dt>
      <dd className="mono">{p.embeddingModell}</dd>
      <dt>Küszöb</dt>
      <dd className="mono">{p.kuszob ?? '— (tesztenként adja meg a Python)'}</dd>
      <dt>Nettó beszéd</dt>
      <dd className="mono">{p.nettoBeszedMp !== null ? `${p.nettoBeszedMp} mp` : '—'}</dd>
      <dt>Mikrofon</dt>
      <dd>{p.mikrofon ?? '—'}</dd>
      <dt>Rögzítve</dt>
      <dd className="mono">{formatDatumIdo(p.rogzitve)}</dd>
    </dl>
  );
}

/** POST /api/voice-profiles — a backend VoiceProfileCreateRequest szabályaival */
function HangprofilForm({
  felhasznaloId,
  onDone,
  onCancel,
}: {
  felhasznaloId: number;
  onDone: () => Promise<void>;
  onCancel: () => void;
}) {
  const [wav, setWav] = useState('');
  const [modell, setModell] = useState('pyannote/wespeaker-voxceleb-resnet34-LM');
  const [kuszob, setKuszob] = useState('');
  const [netto, setNetto] = useState('');
  const [mikrofon, setMikrofon] = useState('');
  const [rogzitve, setRogzitve] = useState(mostHelyi());
  const [tried, setTried] = useState(false);
  const { busy, error, run } = useAkcio();
  const ids = { wav: useId(), modell: useId(), kuszob: useId(), netto: useId(), mik: useId(), ido: useId() };

  const kuszobSzam = kuszob.trim() ? parseSzam(kuszob, 3) : null;
  const nettoSzam = netto.trim() ? parseSzam(netto, 2) : null;
  const hiba = !wav.trim()
    ? 'Add meg a hangminta útvonalát.'
    : !modell.trim() || modell.length > 128
      ? 'Add meg a beágyazó modellt (legfeljebb 128 karakter).'
      : kuszob.trim() && (kuszobSzam === null || kuszobSzam < 0 || kuszobSzam > 1)
        ? 'A küszöb 0 és 1 közötti szám, legfeljebb 3 tizedessel.'
        : netto.trim() && (nettoSzam === null || nettoSzam < 0 || nettoSzam >= 10000)
          ? 'A nettó beszédidő nemnegatív szám (mp), legfeljebb 2 tizedessel.'
          : !rogzitve
            ? 'Add meg a rögzítés idejét.'
            : null;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (hiba) return;
    const p = await run(() =>
      api.createHangprofil({
        felhasznaloId,
        wavUt: wav.trim(),
        embeddingModell: modell.trim(),
        kuszob: kuszobSzam,
        nettoBeszedMp: nettoSzam,
        mikrofon: mikrofon.trim() || null,
        rogzitve: helyiToIso(rogzitve),
      }),
    );
    if (p) await onDone();
  }

  return (
    <form className="tabform tabform--inset" onSubmit={submit} noValidate aria-label="Új hangprofil">
      <strong>Új hangprofil</strong>
      <span className="meta">A jelenlegi aktív profil inaktív lesz (az előzményben megmarad).</span>
      <div className="formgrid">
        <div className="field">
          <label htmlFor={ids.wav}>Hangminta (WAV) útvonala</label>
          <input
            id={ids.wav}
            className="input mono"
            placeholder="orvos_profilok/drkovacs.wav"
            value={wav}
            onChange={(e) => setWav(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor={ids.modell}>Beágyazó modell</label>
          <input
            id={ids.modell}
            className="input mono"
            maxLength={128}
            value={modell}
            onChange={(e) => setModell(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor={ids.kuszob}>
            Küszöb <span className="meta">(0–1, nem kötelező)</span>
          </label>
          <input
            id={ids.kuszob}
            className="input mono"
            inputMode="decimal"
            value={kuszob}
            onChange={(e) => setKuszob(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor={ids.netto}>
            Nettó beszéd (mp) <span className="meta">(nem kötelező)</span>
          </label>
          <input
            id={ids.netto}
            className="input mono"
            inputMode="decimal"
            value={netto}
            onChange={(e) => setNetto(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor={ids.mik}>Mikrofon</label>
          <input
            id={ids.mik}
            className="input"
            maxLength={255}
            value={mikrofon}
            onChange={(e) => setMikrofon(e.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor={ids.ido}>Rögzítés ideje</label>
          <input
            id={ids.ido}
            type="datetime-local"
            className="input mono"
            value={rogzitve}
            onChange={(e) => setRogzitve(e.target.value)}
          />
        </div>
      </div>
      {tried && hiba && <p className="form-error">{hiba}</p>}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="actions">
        <button type="submit" className="btn btn--primary" disabled={busy}>
          <Icon name="check" strokeWidth={2.6} />
          {busy ? 'Mentés…' : 'Hangprofil mentése'}
        </button>
        <button type="button" className="btn" onClick={onCancel} disabled={busy}>
          Mégse
        </button>
      </div>
    </form>
  );
}
