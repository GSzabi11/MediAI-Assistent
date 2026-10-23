import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import type { Ellatas, EllatasTipus } from '../api/types';
import { formatDatumIdo } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { FelhasznaloNev } from './FelhasznaloNev';
import { EpikrizisPanel } from './EpikrizisPanel';
import { Icon } from './Icon';
import { Segmented } from './Segmented';
import { ErrorState, Loading } from './shell';

const ellatasFelirat: Record<EllatasTipus, string> = {
  sbo: 'Sürgősségi (SBO)',
  ambulans: 'Ambuláns',
  echo: 'Echokardiográfia',
};

/**
 * Ellátások: nyitás (a belépett orvos nevében), lezárás (egyszer, DB-idővel), és a
 * hozzájuk rögzített felvételek. Több nyitott ellátás is lehet (pl. SBO alatt echo).
 */
export function EllatasPanel() {
  const { patient } = usePatient();
  const { state, reload, refresh } = useAsync(() => api.listEllatasok(patient.id), [patient.id]);
  const [tipus, setTipus] = useState<EllatasTipus>('sbo');
  const nyitas = useAkcio();

  async function nyit() {
    const e = await nyitas.run(() => api.createEllatas(patient.id, tipus));
    if (e) await refresh();
  }

  return (
    <div className="tabbody">
      <div className="row-baseline">
        <h2 className="h2 h2--sm">Ellátások</h2>
        <span className="meta">egy betegnél egyszerre több nyitott ellátás is lehet</span>
      </div>
      <div className="row-end">
        <div className="field">
          <span className="field__legend">Új ellátás típusa</span>
          <Segmented
            label="Ellátás típusa"
            value={tipus}
            onChange={setTipus}
            options={[
              ['sbo', 'SBO'],
              ['ambulans', 'Ambuláns'],
              ['echo', 'Echo'],
            ]}
          />
        </div>
        <button type="button" className="btn btn--primary" onClick={() => void nyit()} disabled={nyitas.busy}>
          <Icon name="plus" size={16} strokeWidth={2.4} />
          {nyitas.busy ? 'Nyitás…' : 'Ellátás nyitása'}
        </button>
      </div>
      {nyitas.error && (
        <p className="form-error" role="alert">
          {nyitas.error}
        </p>
      )}

      {state.status === 'loading' && <Loading what="Ellátások" />}
      {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
      {state.status === 'ok' && state.data.some((e) => e.patientId !== patient.id) && (
        <ErrorState error={new Error('Az ellátások egy része nem az aktív beteghez tartozik — rejtve.')} />
      )}
      {state.status === 'ok' && state.data.every((e) => e.patientId === patient.id) && (
        <>
          {state.data.length === 0 && <p className="empty">Még nincs ellátás.</p>}
          <ul className="itemlist">
            {state.data.map((e) => (
              <EllatasSor key={e.id} ellatas={e} onValtozott={refresh} />
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

function EllatasSor({ ellatas, onValtozott }: { ellatas: Ellatas; onValtozott: () => Promise<void> }) {
  const { patient, felvetelek } = usePatient();
  const [reszletek, setReszletek] = useState(false);
  const [epikrizis, setEpikrizis] = useState(false);
  const zaras = useAkcio();
  const felvetelei = felvetelek.filter((f) => f.ellatasId === ellatas.id);
  const nyitott = ellatas.veg === null;

  async function lezar() {
    const e = await zaras.run(() => api.lezarEllatas(ellatas.id));
    if (e) await onValtozott();
  }

  return (
    <li className={`item${nyitott ? ' item--open' : ''}`}>
      <div className="item__row">
        <span className={`stat ${nyitott ? 'stat--nyitott' : 'stat--kesz'}`}>{nyitott ? 'Nyitott' : 'Lezárt'}</span>
        <strong>
          #{ellatas.id} · {ellatasFelirat[ellatas.tipus]}
        </strong>
        <span className="meta">
          {formatDatumIdo(ellatas.kezdet)}
          {ellatas.veg && ` – ${formatDatumIdo(ellatas.veg)}`} · <FelhasznaloNev id={ellatas.orvosId} />
        </span>
        <div className="actions push-right">
          <button
            type="button"
            className="btn btn--sm"
            aria-expanded={reszletek}
            onClick={() => setReszletek((r) => !r)}
          >
            Részletek
          </button>
          <button
            type="button"
            className="btn btn--sm"
            aria-expanded={epikrizis}
            onClick={() => setEpikrizis((v) => !v)}
          >
            <Icon name="doc" size={14} />
            Epikrízis
          </button>
          {nyitott && (
            <button type="button" className="btn btn--sm" onClick={() => void lezar()} disabled={zaras.busy}>
              <Icon name="stop" size={14} />
              {zaras.busy ? 'Lezárás…' : 'Lezárás'}
            </button>
          )}
        </div>
      </div>
      {zaras.error && (
        <p className="form-error" role="alert">
          {zaras.error}
        </p>
      )}
      {reszletek && <EllatasReszlet id={ellatas.id} />}
      {epikrizis && <EpikrizisPanel ellatas={ellatas} />}
      {felvetelei.length > 0 && (
        <div className="item__sub">
          <span className="meta">Felvételek:</span>
          {felvetelei.map((f) => (
            <Link key={f.id} to={`/betegek/${patient.id}?nezet=atirat&felvetel=${f.id}`} className="src src--small">
              <Icon name="mic" size={12} />
              Felvétel #{f.id}
            </Link>
          ))}
        </div>
      )}
    </li>
  );
}

/** GET /api/medical-cares/{id} — friss állapot (pl. ha közben máshol lezárták) */
function EllatasReszlet({ id }: { id: number }) {
  const { patient } = usePatient();
  const { state, reload } = useAsync(() => api.getEllatas(id), [id]);
  if (state.status === 'loading') return <Loading what="Ellátás" />;
  if (state.status === 'error') return <ErrorState error={state.error} onRetry={reload} />;
  const e = state.data;
  if (e.patientId !== patient.id) return <ErrorState error={new Error('Az ellátás nem az aktív beteghez tartozik.')} />;
  return (
    <dl className="deflist">
      <dt>Azonosító</dt>
      <dd className="mono">#{e.id}</dd>
      <dt>Típus</dt>
      <dd>{ellatasFelirat[e.tipus]}</dd>
      <dt>Kezdete</dt>
      <dd className="mono">{formatDatumIdo(e.kezdet)}</dd>
      <dt>Vége</dt>
      <dd className="mono">{e.veg ? formatDatumIdo(e.veg) : 'nyitott'}</dd>
      <dt>Megnyitotta</dt>
      <dd>
        <FelhasznaloNev id={e.orvosId} />
      </dd>
    </dl>
  );
}
