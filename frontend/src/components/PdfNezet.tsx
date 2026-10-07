import { useEffect, useRef, useState } from 'react';
import * as pdfjs from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import { api } from '../api';
import type { ForrasDoboz } from '../api/types';
import { ErrorState, Loading } from './shell';

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;

/**
 * Az eredeti PDF oldalanként kirajzolva (pdf.js), a megadott sor kiemelésével. A kiemelés a
 * laphoz normált (0–1) téglalap — a dokumentum-feldolgozás rögzítette (lab_result.source_*),
 * nem itt keressük —, ezért százalékos pozícióval a lap átméretezésekor is a helyén marad.
 */
// Nagyítási lépcsők; alapból akkora, hogy a lap legalább OLVASHATO_SZELESSEG px (≈ valós méret) legyen —
// a betegoldal dokumentum-oszlopa keskeny (~300 px), a teljes lap ott olvashatatlan
const LEPCSOK = [1, 1.5, 2, 2.5, 3, 4];
const OLVASHATO_SZELESSEG = 600;

export function PdfNezet({ dokumentumId, kiemeles }: { dokumentumId: number; kiemeles: ForrasDoboz | null }) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [alapSzelesseg, setAlapSzelesseg] = useState(0);
  const [nagyitas, setNagyitas] = useState<number | null>(null);
  const zoom =
    nagyitas ??
    (alapSzelesseg > 0
      ? (LEPCSOK.find((l) => l * alapSzelesseg >= OLVASHATO_SZELESSEG) ?? LEPCSOK[LEPCSOK.length - 1])
      : 1);
  const markRef = useRef<HTMLDivElement>(null);
  const [allapot, setAllapot] = useState<'betoltes' | 'kesz' | Error>('betoltes');
  const [oldalak, setOldalak] = useState<{ szam: number; arany: number }[]>([]);
  const canvasRefs = useRef(new Map<number, HTMLCanvasElement>());
  const [pdf, setPdf] = useState<pdfjs.PDFDocumentProxy | null>(null);

  // A fájl fetch-csel jön (X-User-Id fejléccel — a hozzáférési naplóban látszik, ki nyitotta meg).
  // Dokumentumonként új komponens (a szülő key-je), ezért a kezdő 'betoltes' állapot elég.
  useEffect(() => {
    let elvetve = false;
    let task: ReturnType<typeof pdfjs.getDocument> | null = null;
    (async () => {
      try {
        const blob = await api.letoltFajl(dokumentumId);
        if (elvetve) return;
        task = pdfjs.getDocument({ data: new Uint8Array(await blob.arrayBuffer()) });
        const doc = await task.promise;
        if (elvetve) return;
        const meretek = [];
        for (let n = 1; n <= doc.numPages; n++) {
          const vp = (await doc.getPage(n)).getViewport({ scale: 1 });
          meretek.push({ szam: n, arany: vp.height / vp.width });
        }
        if (elvetve) return;
        setOldalak(meretek);
        setPdf(doc);
      } catch (e) {
        if (!elvetve) setAllapot(e instanceof Error ? e : new Error(String(e)));
      }
    })();
    return () => {
      elvetve = true;
      void task?.destroy();
    };
  }, [dokumentumId]);

  // A görgető terület szélessége (a panel átméretezésekor is)
  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    // Csak valódi méretváltozás indít újrarajzolást (a görgetősáv megjelenése 1-2 px-et ugrat)
    const ro = new ResizeObserver(() =>
      setAlapSzelesseg((elozo) => (Math.abs(elozo - el.clientWidth) > 8 ? el.clientWidth : elozo)),
    );
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Kirajzolás a nagyított szélességhez (és a kijelző pixelsűrűségéhez) igazítva. Egy vászonra
  // egyszerre csak egy pdf.js-kirajzolás futhat: új kirajzolás (méret, nagyítás, nézetváltás)
  // előtt a takarító függvény megszakítja a még futókat.
  useEffect(() => {
    if (!pdf || alapSzelesseg === 0) return;
    let elvetve = false;
    const futo: pdfjs.RenderTask[] = [];
    const szelesseg = alapSzelesseg * zoom;
    const dpr = window.devicePixelRatio || 1;
    (async () => {
      try {
        for (const { szam } of oldalak) {
          const canvas = canvasRefs.current.get(szam);
          if (!canvas) continue;
          const page = await pdf.getPage(szam);
          if (elvetve) return;
          const vp = page.getViewport({ scale: (szelesseg * dpr) / page.getViewport({ scale: 1 }).width });
          canvas.width = Math.floor(vp.width);
          canvas.height = Math.floor(vp.height);
          const renderTask = page.render({ canvas, viewport: vp });
          futo.push(renderTask);
          await renderTask.promise;
          if (elvetve) return;
        }
        setAllapot('kesz');
      } catch (e) {
        // a megszakított (felülírt) kirajzolás nem hiba
        if (elvetve || e instanceof pdfjs.RenderingCancelledException) return;
        setAllapot(e instanceof Error ? e : new Error(String(e)));
      }
    })();
    return () => {
      elvetve = true;
      futo.forEach((t) => t.cancel());
    };
  }, [pdf, oldalak, alapSzelesseg, zoom]);

  useEffect(() => {
    if (allapot === 'kesz')
      // a sor ELEJE (név, érték) legyen látható, ne a közepe
      markRef.current?.scrollIntoView({ block: 'center', inline: 'start', behavior: 'smooth' });
  }, [allapot, kiemeles, zoom]);

  const lepes = (irany: 1 | -1) => {
    const i = LEPCSOK.indexOf(zoom);
    const kov = LEPCSOK[Math.min(LEPCSOK.length - 1, Math.max(0, (i < 0 ? 0 : i) + irany))];
    setNagyitas(kov);
  };

  if (allapot instanceof Error) return <ErrorState error={allapot} />;
  return (
    <div className="pdfview">
      <div className="row-inline pdfview__toolbar" role="group" aria-label="Nagyítás">
        <button type="button" className="btn btn--sm" onClick={() => lepes(-1)} disabled={zoom <= LEPCSOK[0]}>
          −
        </button>
        <span className="meta mono" aria-live="polite">
          {Math.round(zoom * 100)}%
        </span>
        <button
          type="button"
          className="btn btn--sm"
          onClick={() => lepes(1)}
          disabled={zoom >= LEPCSOK[LEPCSOK.length - 1]}
        >
          +
        </button>
        <button type="button" className="btn btn--sm" onClick={() => setNagyitas(1)} disabled={zoom === 1}>
          Szélességhez
        </button>
      </div>
      {allapot === 'betoltes' && <Loading what="PDF" />}
      <div ref={wrapRef} className="pdfview__scroll">
        <div className="pdfview__pages" style={{ width: `${zoom * 100}%` }}>
          {oldalak.map(({ szam, arany }) => (
            <div key={szam} className="pdfview__page" style={{ aspectRatio: `1 / ${arany}` }}>
              <canvas
                ref={(el) => {
                  if (el) canvasRefs.current.set(szam, el);
                  else canvasRefs.current.delete(szam);
                }}
                aria-label={`${szam}. oldal`}
              />
              {kiemeles && kiemeles.oldal === szam && (
                <div
                  ref={markRef}
                  className="pdfview__mark"
                  role="note"
                  aria-label="A kinyert érték sora"
                  style={{
                    left: `${kiemeles.x * 100}%`,
                    top: `${kiemeles.y * 100}%`,
                    width: `${kiemeles.szelesseg * 100}%`,
                    height: `${kiemeles.magassag * 100}%`,
                  }}
                />
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
