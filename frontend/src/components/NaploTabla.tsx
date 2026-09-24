import { Link } from 'react-router-dom';
import type { NaploBejegyzes } from '../api/types';
import { formatDatumIdo } from '../lib/format';
import { FelhasznaloNev } from './FelhasznaloNev';

const muveletFelirat: Record<NaploBejegyzes['muvelet'], string> = {
  READ: 'olvasás',
  WRITE: 'írás',
  EXPORT: 'kivitel',
};

/**
 * Hozzáférési napló táblázat. `oszlop`: a felhasználó (beteg nézőpontjából: ki nézte)
 * vagy a beteg (felhasználó nézőpontjából: kit nézett). A sikertelen (4xx/5xx)
 * próbálkozások kiemelve — biztonsági szempontból pont azok érdekesek.
 */
export function NaploTabla({ sorok, oszlop }: { sorok: NaploBejegyzes[]; oszlop: 'felhasznalo' | 'beteg' }) {
  if (sorok.length === 0) return <p className="empty">Nincs naplóbejegyzés.</p>;
  return (
    <div className="labtable-wrap">
      <table className="labtable">
        <caption className="sr-only">Hozzáférési napló, legújabb elöl</caption>
        <thead>
          <tr>
            <th scope="col">Időpont</th>
            <th scope="col">{oszlop === 'felhasznalo' ? 'Felhasználó' : 'Beteg'}</th>
            <th scope="col">Művelet</th>
            <th scope="col">Erőforrás</th>
            <th scope="col">Válasz</th>
            <th scope="col">IP</th>
          </tr>
        </thead>
        <tbody>
          {sorok.map((s) => (
            <tr key={s.id} className={s.httpStatus >= 400 ? 'labtable__out' : undefined}>
              <td className="mono">{formatDatumIdo(s.idopont)}</td>
              <td>
                {oszlop === 'felhasznalo' ? (
                  <FelhasznaloNev id={s.felhasznaloId} />
                ) : s.patientId !== null ? (
                  <Link to={`/betegek/${s.patientId}`}>beteg #{s.patientId}</Link>
                ) : (
                  <span className="meta">—</span>
                )}
              </td>
              <td>{muveletFelirat[s.muvelet]}</td>
              <td className="mono wrap">{s.eroforras}</td>
              <td className="mono">
                {s.httpStatus}
                {s.httpStatus >= 400 && <span className="sr-only"> (sikertelen)</span>}
              </td>
              <td className="mono">{s.ip ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
