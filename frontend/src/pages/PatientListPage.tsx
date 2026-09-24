import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api';
import type { PatientResponse, PatientSearch } from '../api/types';
import { Icon } from '../components/Icon';
import { NewPatientForm } from '../components/NewPatientForm';
import { AppBar, ErrorState, Loading, PatientBand } from '../components/shell';
import { formatDatum, formatTaj } from '../lib/format';
import { useAsync } from '../lib/useAsync';

/**
 * Beteglista + keresés. Két keresési mód: TAJ, vagy a három természetes
 * azonosító együtt (név + anyja neve + születési dátum) — névre önmagában
 * nem keresünk, mert az névazonosságnál beteg-keveredéshez vezet.
 */
export function PatientListPage() {
  const [query, setQuery] = useState<PatientSearch | null>(null);
  const { state, reload } = useAsync(
    () => (query ? api.searchPatients(query) : api.listPatients()),
    [JSON.stringify(query)],
  );

  const [taj, setTaj] = useState('');
  const [nev, setNev] = useState('');
  const [anyja, setAnyja] = useState('');
  const [szul, setSzul] = useState('');

  const [ujBeteg, setUjBeteg] = useState(false);
  const navigate = useNavigate();

  const tajOk = taj.replace(/\D/g, '').length === 9;
  const termOk = nev.trim() && anyja.trim() && szul;

  return (
    <div className="app">
      <AppBar />
      <PatientBand patient={null} />
      <main className="listpage">
        <div className="row-baseline">
          <h1 className="h1">Beteg keresése</h1>
          {!ujBeteg && (
            <button type="button" className="btn btn--primary push-right" onClick={() => setUjBeteg(true)}>
              <Icon name="plus" size={16} strokeWidth={2.4} />
              Új beteg
            </button>
          )}
        </div>

        {ujBeteg && (
          <NewPatientForm
            onCancel={() => setUjBeteg(false)}
            onCreated={(p) => navigate(`/betegek/${p.id}`)}
          />
        )}

        <div className="searchcard">
          <form
            className="stack-12"
            onSubmit={(e) => {
              e.preventDefault();
              if (tajOk) setQuery({ mod: 'taj', taj: taj.replace(/\D/g, '') });
            }}
          >
            <span className="label">TAJ alapján</span>
            <div className="row-end">
              <div className="field grow">
                <label htmlFor="taj">TAJ-szám</label>
                <input
                  id="taj"
                  className="input mono"
                  inputMode="numeric"
                  placeholder="000 000 000"
                  value={taj}
                  onChange={(e) => setTaj(e.target.value)}
                />
              </div>
              <button type="submit" className="btn btn--primary btn--lg" disabled={!tajOk}>
                <Icon name="search" size={18} strokeWidth={2.4} />
                Keresés
              </button>
            </div>
          </form>

          <div className="searchcard__or">vagy</div>

          <form
            className="stack-12"
            onSubmit={(e) => {
              e.preventDefault();
              if (termOk) setQuery({ mod: 'termeszetes', nev, anyjaNeve: anyja, szuletesiDatum: szul });
            }}
          >
            <span className="label">Természetes azonosítókkal (mindhárom kötelező)</span>
            <div className="row-end">
              <div className="field grow">
                <label htmlFor="nev">Név</label>
                <input id="nev" className="input" value={nev} onChange={(e) => setNev(e.target.value)} required />
              </div>
              <div className="field grow">
                <label htmlFor="anyja">Anyja neve</label>
                <input id="anyja" className="input" value={anyja} onChange={(e) => setAnyja(e.target.value)} required />
              </div>
              <div className="field">
                <label htmlFor="szul">Születési dátum</label>
                <input
                  id="szul"
                  type="date"
                  className="input mono"
                  value={szul}
                  onChange={(e) => setSzul(e.target.value)}
                  required
                />
              </div>
              <button type="submit" className="btn btn--primary btn--lg" disabled={!termOk}>
                <Icon name="search" size={18} strokeWidth={2.4} />
                Keresés
              </button>
            </div>
          </form>
        </div>

        <section className="results" aria-label="Találatok">
          <div className="results__head">
            <h2 className="h2 h2--sm">{query ? 'Találatok' : 'Betegek'}</h2>
            {state.status === 'ok' && <span className="meta">{state.data.length} beteg</span>}
            {query && (
              <button type="button" className="btn btn--sm push-right" onClick={() => setQuery(null)}>
                Keresés törlése
              </button>
            )}
          </div>
          {state.status === 'loading' && <Loading what="Betegek" />}
          {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
          {state.status === 'ok' && <PatientTable patients={state.data} />}
        </section>
      </main>
    </div>
  );
}

function PatientTable({ patients }: { patients: PatientResponse[] }) {
  if (patients.length === 0) return <p className="empty">Nincs találat.</p>;
  return (
    <table className="ptable">
      <thead>
        <tr>
          <th scope="col">Név</th>
          <th scope="col">TAJ</th>
          <th scope="col">Születési dátum</th>
          <th scope="col">Anyja neve</th>
        </tr>
      </thead>
      <tbody>
        {patients.map((p) => (
          <tr key={p.id}>
            <th scope="row">
              <Link to={`/betegek/${p.id}`} className="ptable__link">
                {p.nev}
              </Link>
            </th>
            <td className="mono">{formatTaj(p.taj)}</td>
            <td className="mono">{formatDatum(p.szuletesiDatum)}</td>
            <td>{p.anyjaNeve ?? '—'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
