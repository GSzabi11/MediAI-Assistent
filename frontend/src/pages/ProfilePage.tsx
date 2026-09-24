import { api } from '../api';
import { szerepkorFelirat, useCurrentUser } from '../api/currentUser';
import { HangprofilPanel } from '../components/HangprofilPanel';
import { NaploTabla } from '../components/NaploTabla';
import { AppBar, ErrorState, Loading } from '../components/shell';
import { formatDatumIdo } from '../lib/format';
import { useAsync } from '../lib/useAsync';

/** A belépett felhasználó adatai (GET /users/{id}), hangprofilja és saját hozzáférési naplója. */
export function ProfilePage() {
  const user = useCurrentUser();
  const id = user?.id ?? -1;
  const adat = useAsync(() => api.getFelhasznalo(id), [id]);
  const naplo = useAsync(() => api.listNaploFelhasznalora(id), [id]);
  if (!user) return null;

  return (
    <div className="app">
      <AppBar />
      <main className="listpage">
        <h1 className="h1">Profilom</h1>
        <section className="formcard formcard--pad" aria-label="Felhasználói adatok">
          {adat.state.status === 'loading' && <Loading what="Profil" />}
          {adat.state.status === 'error' && <ErrorState error={adat.state.error} onRetry={adat.reload} />}
          {adat.state.status === 'ok' && (
            <dl className="deflist">
              <dt>Felhasználónév</dt>
              <dd>
                <strong>{adat.state.data.felhasznalonev}</strong>
              </dd>
              <dt>Email</dt>
              <dd>{adat.state.data.email}</dd>
              <dt>Szerepkör</dt>
              <dd>{szerepkorFelirat[adat.state.data.szerepkor]}</dd>
              <dt>Létrehozva</dt>
              <dd className="mono">{formatDatumIdo(adat.state.data.letrehozva)}</dd>
            </dl>
          )}
        </section>

        <div className="formcard formcard--pad">
          <HangprofilPanel felhasznaloId={user.id} szerkesztheto />
        </div>

        <section className="formcard formcard--pad" aria-label="Saját hozzáférési napló">
          <div className="row-baseline">
            <h2 className="h2 h2--sm">Saját hozzáférési napló</h2>
            <span className="meta">mit nyitottam meg és mikor</span>
          </div>
          {naplo.state.status === 'loading' && <Loading what="Napló" />}
          {naplo.state.status === 'error' && <ErrorState error={naplo.state.error} onRetry={naplo.reload} />}
          {naplo.state.status === 'ok' && <NaploTabla sorok={naplo.state.data} oszlop="beteg" />}
        </section>
      </main>
    </div>
  );
}
