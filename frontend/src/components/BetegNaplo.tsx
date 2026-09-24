import { api } from '../api';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { Icon } from './Icon';
import { NaploTabla } from './NaploTabla';
import { ErrorState, Loading } from './shell';

/**
 * GET /api/access-logs?patientId= — ki és mikor fért hozzá a beteg adataihoz. A napló
 * csak hozzáfűzhető, és a beteg törlése után is megmarad. A lekérdezése is naplózódik.
 */
export function BetegNaplo() {
  const { patient } = usePatient();
  const { state, reload } = useAsync(() => api.listNaploBetegre(patient.id), [patient.id]);
  return (
    <div className="tabbody">
      <div className="row-baseline">
        <h2 className="h2 h2--sm">Hozzáférési napló</h2>
        <span className="meta">csak hozzáfűzhető; a sikertelen próbálkozások kiemelve</span>
        <button type="button" className="btn btn--sm push-right" onClick={reload}>
          <Icon name="history" size={14} />
          Frissítés
        </button>
      </div>
      {state.status === 'loading' && <Loading what="Napló" />}
      {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
      {state.status === 'ok' && state.data.some((s) => s.patientId !== patient.id) && (
        <ErrorState error={new Error('A napló nem az aktív beteghez tartozik — rejtve.')} />
      )}
      {state.status === 'ok' && state.data.every((s) => s.patientId === patient.id) && (
        <NaploTabla sorok={state.data} oszlop="felhasznalo" />
      )}
    </div>
  );
}
