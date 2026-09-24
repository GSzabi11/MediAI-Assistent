import { useState, type FormEvent } from 'react';
import { api } from '../api';
import type { PatientResponse } from '../api/types';
import { Icon } from './Icon';

/**
 * Új beteg felvétele (POST /api/patients). A TAJ hiányát külön jelölőnégyzettel
 * kell kimondani: egy elfelejtett TAJ így nem keveredhet össze egy szándékosan
 * azonosítatlan (SBO, külföldi) beteggel.
 */
export function NewPatientForm({
  onCreated,
  onCancel,
}: {
  onCreated: (p: PatientResponse) => void;
  onCancel: () => void;
}) {
  const [nev, setNev] = useState('');
  const [nincsTaj, setNincsTaj] = useState(false);
  const [taj, setTaj] = useState('');
  const [anyja, setAnyja] = useState('');
  const [szul, setSzul] = useState('');
  const [lakcim, setLakcim] = useState('');
  const [tried, setTried] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const tajSzam = taj.replace(/\D/g, '');
  const ma = new Date().toISOString().slice(0, 10);
  // A backend PatientCreateRequest szabályai, hogy a hiba itt, érthetően jelenjen meg
  const hibak = {
    nev: !nev.trim() ? 'Add meg a beteg nevét.' : null,
    taj: !nincsTaj && tajSzam.length !== 9 ? 'A TAJ 9 számjegy — vagy jelöld, hogy nincs TAJ.' : null,
    anyja: !anyja.trim() ? 'Add meg az anyja nevét.' : null,
    szul: !szul ? 'Add meg a születési dátumot.' : szul > ma ? 'A születési dátum nem lehet a jövőben.' : null,
  };
  const ok = Object.values(hibak).every((h) => h === null);
  const hiba = (k: keyof typeof hibak) =>
    tried && hibak[k] ? (
      <span className="form-error" id={`np-${k}-error`}>
        {hibak[k]}
      </span>
    ) : null;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (!ok) return;
    setBusy(true);
    setError(null);
    try {
      onCreated(
        await api.createPatient({
          nev,
          taj: nincsTaj ? null : tajSzam,
          anyjaNeve: anyja,
          szuletesiDatum: szul,
          lakcim: lakcim || null,
        }),
      );
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="formcard" onSubmit={submit} noValidate aria-label="Új beteg felvétele">
      <div className="formcard__head">
        <h2 className="h2 h2--sm">Új beteg felvétele</h2>
        <button
          type="button"
          className="iconbtn iconbtn--ghost iconbtn--sm push-right"
          aria-label="Űrlap bezárása"
          onClick={onCancel}
        >
          <Icon name="x" size={18} />
        </button>
      </div>
      <div className="formcard__grid">
        <div className="field">
          <label htmlFor="np-nev">Név</label>
          <input
            id="np-nev"
            className="input"
            value={nev}
            onChange={(e) => setNev(e.target.value)}
            aria-invalid={tried && !!hibak.nev}
            aria-describedby="np-nev-error"
            autoFocus
          />
          {hiba('nev')}
        </div>
        <div className="field">
          <label htmlFor="np-taj">TAJ-szám</label>
          <input
            id="np-taj"
            className="input mono"
            inputMode="numeric"
            placeholder={nincsTaj ? 'nincs TAJ' : '000 000 000'}
            value={nincsTaj ? '' : taj}
            onChange={(e) => setTaj(e.target.value)}
            disabled={nincsTaj}
            aria-invalid={tried && !!hibak.taj}
            aria-describedby="np-taj-error"
          />
          <label className="checkrow" htmlFor="np-nincs-taj">
            <input
              id="np-nincs-taj"
              type="checkbox"
              checked={nincsTaj}
              onChange={(e) => setNincsTaj(e.target.checked)}
            />
            Nincs TAJ — azonosítatlan vagy külföldi beteg
          </label>
          {hiba('taj')}
        </div>
        <div className="field">
          <label htmlFor="np-anyja">Anyja neve</label>
          <input
            id="np-anyja"
            className="input"
            value={anyja}
            onChange={(e) => setAnyja(e.target.value)}
            aria-invalid={tried && !!hibak.anyja}
            aria-describedby="np-anyja-error"
          />
          {hiba('anyja')}
        </div>
        <div className="field">
          <label htmlFor="np-szul">Születési dátum</label>
          <input
            id="np-szul"
            type="date"
            className="input mono"
            max={ma}
            value={szul}
            onChange={(e) => setSzul(e.target.value)}
            aria-invalid={tried && !!hibak.szul}
            aria-describedby="np-szul-error"
          />
          {hiba('szul')}
        </div>
        <div className="field formcard__wide">
          <label htmlFor="np-lakcim">
            Lakcím <span className="meta">(nem kötelező)</span>
          </label>
          <input id="np-lakcim" className="input" value={lakcim} onChange={(e) => setLakcim(e.target.value)} />
        </div>
      </div>
      {error && (
        <p className="form-error formcard__error" role="alert">
          {error}
        </p>
      )}
      <div className="formcard__actions">
        <button type="submit" className="btn btn--primary btn--lg" disabled={busy}>
          <Icon name="plus" size={18} strokeWidth={2.4} />
          {busy ? 'Felvétel…' : 'Beteg felvétele'}
        </button>
        <button type="button" className="btn btn--lg" onClick={onCancel} disabled={busy}>
          Mégse
        </button>
      </div>
    </form>
  );
}
