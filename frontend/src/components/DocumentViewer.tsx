import { useEffect, useMemo, useRef } from 'react';
import { api } from '../api';
import { formatDatum } from '../lib/format';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { Icon } from './Icon';
import { ErrorState, Loading, PatientMatch } from './shell';

export interface Kiemeles {
  kezdet: number;
  veg: number;
}

/**
 * Dokumentumnézet, ami a hivatkozott karaktertartományt kiemeli és odagörget.
 * A tartomány [kezdet, veg), Unicode code pointban — ugyanúgy, ahogy a backend
 * (Document.textSpan) és a Python számol. A JS stringek UTF-16 egységben
 * indexelnek, ezért a szöveget code pointokra bontva vágjuk.
 */
export function DocumentViewer({ dokumentumId, kiemeles }: { dokumentumId: number; kiemeles: Kiemeles | null }) {
  const { patient } = usePatient();
  const { state, reload } = useAsync(() => api.getDokumentum(dokumentumId), [dokumentumId]);
  const markRef = useRef<HTMLElement>(null);
  const chars = useMemo(
    () => (state.status === 'ok' && state.data.szoveg !== null ? Array.from(state.data.szoveg) : []),
    [state],
  );

  useEffect(() => {
    markRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [state.status, kiemeles?.kezdet, kiemeles?.veg]);

  if (state.status === 'loading') return <Loading what="Dokumentum" />;
  if (state.status === 'error') return <ErrorState error={state.error} onRetry={reload} />;
  const doc = state.data;

  const sameP = doc.patientId === patient.id;
  const k =
    kiemeles && kiemeles.kezdet >= 0 && kiemeles.veg <= chars.length && kiemeles.kezdet < kiemeles.veg
      ? kiemeles
      : null;
  const part = (from: number, to?: number) => chars.slice(from, to).join('');

  return (
    <div className="docview">
      <div className="panel__head">
        <div className="row-baseline">
          <h2 className="h2 h2--sm">{doc.cim}</h2>
          <span className="meta">{[doc.osztaly, formatDatum(doc.datum)].filter(Boolean).join(' · ')}</span>
          {sameP && (
            <a className="btn btn--sm push-right" href={doc.fajlUrl} target="_blank" rel="noreferrer">
              <Icon name="doc" size={14} />
              Eredeti fájl
            </a>
          )}
        </div>
        <PatientMatch active={patient} contentPatientId={doc.patientId} what="Dokumentum" />
      </div>
      {sameP && doc.szoveg === null && (
        <p className="state">
          <Icon name="info" />
          Ehhez a dokumentumhoz még nincs kinyert szöveg — csak az eredeti fájl érhető el.
        </p>
      )}
      {sameP && doc.szoveg !== null && (
        <div className={`docview__text${doc.tipus === 'labor' ? ' mono' : ''}`}>
          {k ? (
            <>
              {part(0, k.kezdet)}
              <span className="range-label mono" aria-hidden="true">
                kar. {k.kezdet}–{k.veg}
              </span>
              <mark ref={markRef}>{part(k.kezdet, k.veg)}</mark>
              {part(k.veg)}
            </>
          ) : (
            doc.szoveg
          )}
        </div>
      )}
      {kiemeles && !k && sameP && doc.szoveg !== null && (
        <p className="form-error" role="alert">
          A hivatkozott tartomány ({kiemeles.kezdet}–{kiemeles.veg}) kívül esik a dokumentumon.
        </p>
      )}
    </div>
  );
}
