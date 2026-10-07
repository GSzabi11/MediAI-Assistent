import { lazy, Suspense, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { api } from '../api';
import type { DokumentumResponse, ForrasDoboz } from '../api/types';
import { formatDatum, formatMeret } from '../lib/format';
import { kijeloltTartomany } from '../lib/kijeles';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { IdezetForm } from './allitas/IdezetForm';
import { Icon } from './Icon';
import { KinyertSzovegForm, RagDarabok } from './DokumentumReszek';
import { Segmented } from './Segmented';
import { ErrorState, Loading, PatientMatch } from './shell';

// A pdf.js nagy: csak akkor töltődik le, ha valaki ténylegesen megnyit egy PDF-et
const PdfNezet = lazy(() => import('./PdfNezet').then((m) => ({ default: m.PdfNezet })));

export interface Kiemeles {
  kezdet: number;
  veg: number;
}

type Kijeloles = { kezdet: number; veg: number; szoveg: string };

/**
 * Dokumentumnézet, ami a hivatkozott karaktertartományt kiemeli és odagörget.
 * A tartomány [kezdet, veg), Unicode code pointban — ugyanúgy, ahogy a backend
 * (Document.textSpan) és a Python számol. A JS stringek UTF-16 egységben
 * indexelnek, ezért a szöveget code pointokra bontva vágjuk.
 *
 * A kijelölt szövegrész szó szerinti idézet-állításként rögzíthető.
 */
export function DocumentViewer({
  dokumentumId,
  kiemeles,
  pdfNezet = false,
  pdfKiemeles = null,
}: {
  dokumentumId: number;
  kiemeles: Kiemeles | null;
  /** laborsorból nyitva: az eredeti PDF-fel indul */
  pdfNezet?: boolean;
  /** a kiemelendő sor a PDF-ben (a kinyeréskor rögzített hely) */
  pdfKiemeles?: ForrasDoboz | null;
}) {
  const [mod, setMod] = useState<'szoveg' | 'pdf'>(pdfNezet ? 'pdf' : 'szoveg');
  const { patient, frissit, dokumentumok } = usePatient();
  // A lista (PatientPage) feldolgozás alatt 5 mp-enként frissül; ha ott az állapot változik
  // (PROCESSING → PROCESSED/FAILED), a megnyitott dokumentum is újratöltődik a kinyert szöveggel
  const listaAllapot = dokumentumok.find((d) => d.id === dokumentumId)?.allapot;
  const { state, reload } = useAsync(() => api.getDokumentum(dokumentumId), [dokumentumId, listaAllapot]);
  const markRef = useRef<HTMLElement>(null);
  const textRef = useRef<HTMLDivElement>(null);
  const [kijeloles, setKijeloles] = useState<Kijeloles | null>(null);
  const [idezet, setIdezet] = useState<Kijeloles | null>(null);
  const letoltes = useAkcio();
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
  // Minden kijelölhető rész egy `data-start` elem: ebből számolja a kijelölés a code-point pozíciót
  const resz = (from: number, to?: number): ReactNode => (
    <span data-start={from}>{chars.slice(from, to).join('')}</span>
  );

  const figyelKijelolest = () => {
    if (idezet || !textRef.current || doc.szoveg === null) return;
    setKijeloles(kijeloltTartomany(textRef.current, doc.szoveg));
  };

  // A fájl fetch-csel jön (X-User-Id fejléccel → a naplóban látszik, ki vitte ki). Az ablakot
  // a kattintásra azonnal megnyitjuk, különben a böngésző felugró-ablak-blokkolója elnyelné.
  async function eredetiFajl() {
    const ablak = window.open('about:blank', '_blank');
    const blob = await letoltes.run(() => api.letoltFajl(doc.id));
    if (!blob) {
      ablak?.close();
      return;
    }
    const url = URL.createObjectURL(blob);
    if (ablak) ablak.location.href = url;
    else {
      const a = document.createElement('a');
      a.href = url;
      a.download = `dokumentum-${doc.id}`;
      a.click();
    }
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }

  return (
    <div className="docview">
      <div className="panel__head">
        <div className="row-baseline">
          <h2 className="h2 h2--sm">{doc.cim}</h2>
          <span className="meta">
            {[doc.osztaly, formatDatum(doc.datum), doc.mimeTipus, formatMeret(doc.meretBajt)]
              .filter(Boolean)
              .join(' · ')}
          </span>
          {sameP && (
            <button
              type="button"
              className="btn btn--sm push-right"
              onClick={() => void eredetiFajl()}
              disabled={letoltes.busy}
            >
              <Icon name="download" size={14} />
              Eredeti fájl
            </button>
          )}
        </div>
        {letoltes.error && (
          <p className="form-error" role="alert">
            {letoltes.error}
          </p>
        )}
        <PatientMatch active={patient} contentPatientId={doc.patientId} what="Dokumentum" />
      </div>

      {sameP && (
        <div className="docview__section">
          <FeldolgozasInfo doc={doc} />
          {doc.mimeTipus === 'application/pdf' && (
            <Segmented
              label="Megjelenítés"
              value={mod}
              onChange={setMod}
              options={[
                ['szoveg', 'Kinyert szöveg'],
                ['pdf', 'Eredeti PDF'],
              ]}
            />
          )}
          {mod === 'pdf' && doc.mimeTipus === 'application/pdf' && (
            <>
              {pdfNezet && !pdfKiemeles && (
                <p className="meta">
                  Ennek a mérésnek nincs rögzített helye a leletben (a hely-rögzítés előtt dolgozták fel) — kiemelés
                  nélkül.
                </p>
              )}
              <Suspense fallback={<Loading what="PDF-megjelenítő" />}>
                <PdfNezet dokumentumId={doc.id} kiemeles={pdfKiemeles} />
              </Suspense>
            </>
          )}
        </div>
      )}

      {sameP && mod === 'szoveg' && doc.szoveg === null && doc.allapot !== 'feldolgozas' && (
        <KinyertSzovegForm
          dokumentum={doc}
          onSaved={() => {
            reload();
            void frissit();
          }}
        />
      )}

      {sameP && (mod === 'szoveg' || doc.mimeTipus !== 'application/pdf') && doc.szoveg !== null && (
        <>
          <div
            ref={textRef}
            className={`docview__text${doc.tipus === 'labor' ? ' mono' : ''}`}
            onMouseUp={figyelKijelolest}
            onKeyUp={figyelKijelolest}
          >
            {k ? (
              <>
                {resz(0, k.kezdet)}
                <span className="range-label mono" aria-hidden="true">
                  kar. {k.kezdet}–{k.veg}
                </span>
                <mark ref={markRef}>{resz(k.kezdet, k.veg)}</mark>
                {resz(k.veg)}
              </>
            ) : (
              resz(0)
            )}
          </div>

          {kijeloles && !idezet && (
            <div className="quotebar" role="status">
              <span className="meta">
                Kijelölve:{' '}
                <span className="mono">
                  kar. {kijeloles.kezdet}–{kijeloles.veg}
                </span>
              </span>
              <button type="button" className="btn btn--sm btn--primary" onClick={() => setIdezet(kijeloles)}>
                <Icon name="quote" size={14} />
                Idézet állításként…
              </button>
              <button type="button" className="btn btn--sm" onClick={() => setKijeloles(null)}>
                Mégse
              </button>
            </div>
          )}
          {idezet && (
            <IdezetForm
              forras={{ tipus: 'dokumentum', dokumentumId: doc.id, kezdet: idezet.kezdet, veg: idezet.veg }}
              szoveg={idezet.szoveg}
              helyLeiras={`${doc.cim}, kar. ${idezet.kezdet}–${idezet.veg}`}
              onDone={() => {
                setIdezet(null);
                setKijeloles(null);
                window.getSelection()?.removeAllRanges();
              }}
              onCancel={() => setIdezet(null)}
            />
          )}
          {!kijeloles && !idezet && (
            <p className="meta docview__hint">
              Jelölj ki egy szövegrészt, hogy szó szerinti idézet-állításként rögzítsd.
            </p>
          )}
        </>
      )}

      {kiemeles && !k && sameP && doc.szoveg !== null && (
        <p className="form-error docview__hint" role="alert">
          A hivatkozott tartomány ({kiemeles.kezdet}–{kiemeles.veg}) kívül esik a dokumentumon.
        </p>
      )}

      {sameP && <RagDarabok dokumentum={doc} />}
    </div>
  );
}

/** Az automatikus (AI) feldolgozás állapota, kihagyásai és a kinyerő modell (HOGY). */
function FeldolgozasInfo({ doc }: { doc: DokumentumResponse }) {
  if (doc.allapot === 'feldolgozas')
    return (
      <p className="meta" role="status">
        AI-feldolgozás folyamatban… A típus, a klinikai dátum, a laborértékek és az állítások automatikusan megjelennek,
        amint elkészül (a feldolgozás soronként, egyszerre egy dokumentumon fut).
      </p>
    );
  return (
    <>
      {doc.allapot === 'hiba' && (
        <p className="form-error" role="alert">
          Az automatikus feldolgozás sikertelen: {doc.feldolgozasiHiba}
        </p>
      )}
      {doc.kinyero && (
        <p className="meta">
          Automatikusan feldolgozva · modell: <span className="mono">{doc.kinyero.modell}</span> · seed{' '}
          <span className="mono">{doc.kinyero.seed}</span> · prompt{' '}
          <span className="mono" title={doc.kinyero.promptHash}>
            {doc.kinyero.promptHash.slice(0, 12)}
          </span>{' '}
          · <span className="mono">{doc.kinyero.verzio}</span>
        </p>
      )}
      {doc.figyelmeztetesek.length > 0 && (
        <details className="meta">
          <summary>A feldolgozás {doc.figyelmeztetesek.length} tételt tudatosan kihagyott</summary>
          <ul>
            {doc.figyelmeztetesek.map((w, i) => (
              <li key={i}>{w}</li>
            ))}
          </ul>
        </details>
      )}
    </>
  );
}
