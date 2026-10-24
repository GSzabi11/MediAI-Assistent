import { useEffect, useId, useState } from 'react';
import { api } from '../api';
import type { FelvetelOsszefoglalo } from '../api/types';
import { formatDatumIdo } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { useIrhato } from '../patient/EllatasKontextus';
import { Icon } from './Icon';
import { ErrorState, Loading } from './shell';

/**
 * Összefoglaló a teljes átiratból. A felvétel kész állapotakor magától elkészül, gombbal
 * újragenerálható. A régi kulcsszó-ellenőrzés figyelmeztetése nem jelenik meg (a csoportok
 * kiváltották, és szó szerinti egyezése az átfogalmazott pontot is hiányzónak látta). Mondatonként
 * a hivatkozott megszólalásokra ugrik (turnId). Modell-kimenet: az orvos ellenőrzi.
 */
export function FelvetelOsszefoglaloPanel({
  felvetelId,
  onUgras,
}: {
  felvetelId: number;
  onUgras: (turnIds: number[]) => void;
}) {
  const lista = useAsync(() => api.listFelvetelOsszefoglalok(felvetelId), [felvetelId]);
  const { busy, error, run } = useAkcio();
  const irhato = useIrhato();
  const legfrissebb: FelvetelOsszefoglalo | undefined = lista.state.status === 'ok' ? lista.state.data[0] : undefined;
  const keszul = legfrissebb?.allapot === 'feldolgozas';
  const figyelmeztetesek = (legfrissebb?.figyelmeztetesek ?? []).filter((w) => !w.includes('kulcsszavakra'));

  useEffect(() => {
    if (!keszul) return;
    const t = window.setInterval(() => void lista.refresh().catch(() => undefined), 2500);
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keszul]);

  const ujra = () =>
    run(async () => {
      await api.kerFelvetelOsszefoglalo(felvetelId);
      await lista.refresh();
    });

  // Szerkesztés: az orvos szövege kerül előre, az AI eredetije megnézhető marad
  const [szerkeszt, setSzerkeszt] = useState<string | null>(null);
  const szerkesztoId = useId();
  const kezdSzerkesztes = (s: FelvetelOsszefoglalo) =>
    setSzerkeszt(s.szerkesztett ?? s.pontok.map((p) => p.szoveg).join('\n'));
  const ment = (s: FelvetelOsszefoglalo) =>
    run(async () => {
      if (!szerkeszt?.trim()) throw new Error('Az összefoglaló nem lehet üres.');
      await api.szerkesztFelvetelOsszefoglalo(felvetelId, s.id, szerkeszt);
      await lista.refresh();
      setSzerkeszt(null);
    });

  return (
    <section className="kwpanel" aria-label="Összefoglaló">
      <div className="row-baseline">
        <h3 className="h2 h2--sm">Összefoglaló</h3>
        <span className="tag tag--modell">AI · ellenőrizendő</span>
        <button type="button" className="btn btn--sm push-right" onClick={() => void ujra()} disabled={irhato.tiltva || busy || keszul} title={irhato.title}>
          <Icon name="spark" size={14} />
          {legfrissebb ? 'Újragenerálás' : 'Összefoglaló készítése'}
        </button>
      </div>
      {lista.state.status === 'loading' && <Loading what="Összefoglaló" />}
      {lista.state.status === 'error' && <ErrorState error={lista.state.error} onRetry={lista.reload} />}
      {lista.state.status === 'ok' && !legfrissebb && <p className="meta">Még nem készült összefoglaló.</p>}
      {keszul && (
        <p className="meta" role="status">
          <span className="stat stat--feldolgozas">Készül</span> a teljes átiratból…
        </p>
      )}
      {legfrissebb?.allapot === 'hiba' && (
        <p className="form-error" role="alert">
          {legfrissebb.hiba}
        </p>
      )}
      {legfrissebb?.allapot === 'kesz' && (
        <>
          {szerkeszt !== null ? (
            <div className="tabform tabform--inset">
              <label htmlFor={szerkesztoId}>Összefoglaló szerkesztése</label>
              <textarea
                id={szerkesztoId}
                className="textarea summary-editor"
                rows={Math.min(24, Math.max(8, szerkeszt.split('\n').length + 2))}
                maxLength={8000}
                value={szerkeszt}
                onChange={(e) => setSzerkeszt(e.target.value)}
              />
              <span className="meta">Az AI eredeti szövege megmarad, a mentett változat jelenik meg.</span>
              <div className="actions">
                <button
                  type="button"
                  className="btn btn--primary"
                  onClick={() => void ment(legfrissebb)}
                  disabled={irhato.tiltva || busy} title={irhato.title}
                >
                  <Icon name="check" size={14} strokeWidth={2.6} />
                  {busy ? 'Mentés…' : 'Mentés'}
                </button>
                <button type="button" className="btn" onClick={() => setSzerkeszt(null)} disabled={irhato.tiltva || busy} title={irhato.title}>
                  Mégse
                </button>
              </div>
            </div>
          ) : legfrissebb.szerkesztett !== null ? (
            <>
              <p className="meta">
                <span className="badge badge--ai">Orvos szerkesztette</span>{' '}
                {legfrissebb.szerkesztve && formatDatumIdo(legfrissebb.szerkesztve)}
              </p>
              <ul className="itemlist summarypoints">
                {legfrissebb.szerkesztett
                  .split('\n')
                  .filter((l) => l.trim())
                  .map((l, i) => (
                    <li key={i}>{l}</li>
                  ))}
              </ul>
              <details className="turn__orig">
                <summary>Eredeti (AI) összefoglaló</summary>
                <ul className="itemlist summarypoints">
                  {legfrissebb.pontok.map((p, i) => (
                    <li key={i}>
                      {p.szoveg}{' '}
                      {p.turnIds.length > 0 && (
                        <button type="button" className="linkbtn mono" onClick={() => onUgras(p.turnIds)}>
                          [{p.turnIds.map((t) => `#${String(t).padStart(2, '0')}`).join(', ')}]
                        </button>
                      )}
                    </li>
                  ))}
                </ul>
              </details>
            </>
          ) : (
            <ul className="itemlist summarypoints">
              {legfrissebb.pontok.map((p, i) => (
                <li key={i}>
                  {p.szoveg}{' '}
                  {p.turnIds.length > 0 && (
                    <button type="button" className="linkbtn mono" onClick={() => onUgras(p.turnIds)}>
                      [{p.turnIds.map((t) => `#${String(t).padStart(2, '0')}`).join(', ')}]
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {szerkeszt === null && (
            <div className="actions">
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => kezdSzerkesztes(legfrissebb)}
                disabled={irhato.tiltva}
                title={irhato.title}
              >
                <Icon name="pencil" size={14} />
                Szerkesztés
              </button>
            </div>
          )}
          {figyelmeztetesek.length > 0 && (
            <ul className="itemlist">
              {figyelmeztetesek.map((w, i) => (
                <li key={i} className="turn__note">
                  <Icon name="alert" size={13} /> {w}
                </li>
              ))}
            </ul>
          )}
          <p className="meta mono">
            {legfrissebb.modell} · {formatDatumIdo(legfrissebb.kerve)}
          </p>
        </>
      )}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
