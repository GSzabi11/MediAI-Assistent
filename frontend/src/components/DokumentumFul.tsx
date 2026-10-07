import { useSearchParams } from 'react-router-dom';
import type { ForrasDoboz } from '../api/types';
import { usePatient } from '../patient/PatientContext';
import { DocumentViewer, type Kiemeles } from './DocumentViewer';
import { DokumentumFeltoltes } from './DokumentumReszek';
import { Icon } from './Icon';

/** A Dokumentum fül: feltöltő űrlap (kérésre vagy ha még nincs dokumentum) + a kiválasztott dokumentum. */
export function DokumentumFul({
  dokId,
  kiemeles,
  pdfNezet = false,
  pdfKiemeles = null,
}: {
  dokId: number | undefined;
  kiemeles: Kiemeles | null;
  pdfNezet?: boolean;
  pdfKiemeles?: ForrasDoboz | null;
}) {
  const { dokumentumok } = usePatient();
  const [params, setParams] = useSearchParams();
  const feltoltesNyitva = params.get('feltoltes') === '1' || dokumentumok.length === 0;

  return (
    <>
      {feltoltesNyitva ? (
        <DokumentumFeltoltes
          onDone={(id) => setParams({ nezet: 'dokumentum', dok: String(id) })}
          onCancel={dokumentumok.length > 0 ? () => setParams({ nezet: 'dokumentum' }) : undefined}
        />
      ) : (
        <div className="tabbar">
          <button
            type="button"
            className="btn btn--sm"
            onClick={() => setParams({ nezet: 'dokumentum', feltoltes: '1' })}
          >
            <Icon name="upload" size={14} />
            Dokumentum feltöltése
          </button>
        </div>
      )}
      {dokId !== undefined && (
        <DocumentViewer
          key={dokId}
          dokumentumId={dokId}
          kiemeles={kiemeles}
          pdfNezet={pdfNezet}
          pdfKiemeles={pdfKiemeles}
        />
      )}
    </>
  );
}
