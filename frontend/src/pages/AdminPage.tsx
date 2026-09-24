import { useId, useState, type FormEvent } from 'react';
import { api } from '../api';
import { szerepkorFelirat, useCurrentUser } from '../api/currentUser';
import type { Felhasznalo } from '../api/types';
import { HangprofilPanel } from '../components/HangprofilPanel';
import { Icon } from '../components/Icon';
import { NaploTabla } from '../components/NaploTabla';
import { Segmented } from '../components/Segmented';
import { AppBar, ErrorState, Loading } from '../components/shell';
import { formatDatumIdo } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';

type Szuro = 'mind' | 'DOCTOR' | 'ADMIN';

/**
 * Felhasználók (csak adminnak): lista szerepkör szerint (GET /users?role=), keresés
 * felhasználónévre vagy emailre (POST /users/search — az email személyes adat, ezért
 * törzsben), és a kiválasztott felhasználó adatai, hangprofilja, hozzáférési naplója.
 *
 * FIGYELEM: a jogosultságot most csak a felület ellenőrzi — a backend még nem
 * (nincs munkamenet, a Security-fázisban kell).
 */
export function AdminPage() {
  const user = useCurrentUser();
  const [szuro, setSzuro] = useState<Szuro>('mind');
  const admin = user?.role === 'ADMIN';
  // Nem adminnak le sem kérjük a listát (a jogosultságot most csak a felület tartja be)
  const lista = useAsync(
    () => (admin ? api.listFelhasznalok(szuro === 'mind' ? undefined : szuro) : Promise.resolve([])),
    [szuro, admin],
  );
  const [kivalasztott, setKivalasztott] = useState<number | null>(null);

  if (!admin) {
    return (
      <div className="app">
        <AppBar />
        <main className="listpage">
          <p className="state state--error" role="alert">
            <Icon name="lock" />
            Ez az oldal csak admin szerepkörrel érhető el.
          </p>
        </main>
      </div>
    );
  }

  return (
    <div className="app">
      <AppBar />
      <main className="listpage">
        <h1 className="h1">Felhasználók</h1>
        <Kereses onTalalat={setKivalasztott} />

        <section className="results" aria-label="Felhasználók">
          <div className="results__head">
            <h2 className="h2 h2--sm">Lista</h2>
            {lista.state.status === 'ok' && <span className="meta">{lista.state.data.length} felhasználó</span>}
            <div className="push-right">
              <Segmented
                label="Szerepkör"
                value={szuro}
                onChange={setSzuro}
                options={[
                  ['mind', 'Mind'],
                  ['DOCTOR', 'Orvos'],
                  ['ADMIN', 'Admin'],
                ]}
              />
            </div>
          </div>
          {lista.state.status === 'loading' && <Loading what="Felhasználók" />}
          {lista.state.status === 'error' && <ErrorState error={lista.state.error} onRetry={lista.reload} />}
          {lista.state.status === 'ok' && (
            <FelhasznaloTabla lista={lista.state.data} kivalasztott={kivalasztott} onValaszt={setKivalasztott} />
          )}
        </section>

        {kivalasztott !== null && <FelhasznaloReszlet key={kivalasztott} id={kivalasztott} />}
      </main>
    </div>
  );
}

function Kereses({ onTalalat }: { onTalalat: (id: number) => void }) {
  const [mod, setMod] = useState<'felhasznalonev' | 'email'>('felhasznalonev');
  const [q, setQ] = useState('');
  const [nincs, setNincs] = useState(false);
  const { busy, error, run } = useAkcio();
  const id = useId();

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!q.trim()) return;
    setNincs(false);
    const talalat = await run(() =>
      api.keresFelhasznalo(mod === 'email' ? { email: q.trim() } : { felhasznalonev: q.trim() }),
    );
    if (talalat === null) setNincs(true);
    else if (talalat) onTalalat(talalat.id);
  }

  return (
    <form className="formcard formcard--pad row-end" onSubmit={submit} aria-label="Felhasználó keresése">
      <Segmented
        label="Keresés módja"
        value={mod}
        onChange={setMod}
        options={[
          ['felhasznalonev', 'Felhasználónév'],
          ['email', 'Email'],
        ]}
      />
      <div className="field grow">
        <label htmlFor={id}>{mod === 'email' ? 'Email-cím' : 'Felhasználónév'}</label>
        <input
          id={id}
          className="input"
          type={mod === 'email' ? 'email' : 'text'}
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
      </div>
      <button type="submit" className="btn btn--primary btn--lg" disabled={busy || !q.trim()}>
        <Icon name="search" size={18} strokeWidth={2.4} />
        Keresés
      </button>
      {nincs && (
        <p className="meta" role="status">
          Nincs ilyen felhasználó.
        </p>
      )}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}

function FelhasznaloTabla({
  lista,
  kivalasztott,
  onValaszt,
}: {
  lista: Felhasznalo[];
  kivalasztott: number | null;
  onValaszt: (id: number) => void;
}) {
  if (lista.length === 0) return <p className="empty">Nincs felhasználó.</p>;
  return (
    <table className="ptable">
      <thead>
        <tr>
          <th scope="col">Felhasználónév</th>
          <th scope="col">Email</th>
          <th scope="col">Szerepkör</th>
          <th scope="col">Létrehozva</th>
        </tr>
      </thead>
      <tbody>
        {lista.map((u) => (
          <tr key={u.id} className={u.id === kivalasztott ? 'ptable__row--on' : undefined}>
            <th scope="row">
              <button type="button" className="linkbtn ptable__link" onClick={() => onValaszt(u.id)}>
                {u.felhasznalonev}
              </button>
            </th>
            <td>{u.email}</td>
            <td>{szerepkorFelirat[u.szerepkor]}</td>
            <td className="mono">{formatDatumIdo(u.letrehozva)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** A kiválasztott felhasználó: GET /users/{id}, hangprofil, hozzáférési napló (?userId=) */
function FelhasznaloReszlet({ id }: { id: number }) {
  const adat = useAsync(() => api.getFelhasznalo(id), [id]);
  const naplo = useAsync(() => api.listNaploFelhasznalora(id), [id]);
  return (
    <section className="formcard formcard--pad stack-12" aria-label="Kiválasztott felhasználó">
      {adat.state.status === 'loading' && <Loading what="Felhasználó" />}
      {adat.state.status === 'error' && <ErrorState error={adat.state.error} onRetry={adat.reload} />}
      {adat.state.status === 'ok' && (
        <>
          <div className="row-baseline">
            <h2 className="h2 h2--sm">{adat.state.data.felhasznalonev}</h2>
            <span className="meta">
              {szerepkorFelirat[adat.state.data.szerepkor]} · {adat.state.data.email}
            </span>
          </div>
          <HangprofilPanel felhasznaloId={id} szerkesztheto={false} />
        </>
      )}
      <div className="row-baseline">
        <h2 className="h2 h2--sm">Hozzáférési napló</h2>
        <span className="meta">mit nyitott meg a felhasználó</span>
      </div>
      {naplo.state.status === 'loading' && <Loading what="Napló" />}
      {naplo.state.status === 'error' && <ErrorState error={naplo.state.error} onRetry={naplo.reload} />}
      {naplo.state.status === 'ok' && <NaploTabla sorok={naplo.state.data} oszlop="beteg" />}
    </section>
  );
}
