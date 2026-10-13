import { useEffect, useId, useState, type FormEvent } from 'react';
import { api } from '../api';
import type { FelvetelOsszefoglalo, Kulcsszo } from '../api/types';
import { formatDatumIdo } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { Icon } from './Icon';
import { ErrorState, Loading } from './shell';

/**
 * A kész felvétel kulcsszavai: az AI javaslatai (az orvos döntéséig javaslat, elfogadható /
 * elutasítható) és az orvos + gombbal hozzáadott kifejezései. A kiemelés az átiratban
 * szövegegyezéssel történik (lib/atiratKiemeles), az elutasított kulcsszó nem emelődik ki.
 */
export function KulcsszoPanel({
  felvetelId,
  kulcsszavak,
  onValtozott,
}: {
  felvetelId: number;
  kulcsszavak: Kulcsszo[];
  onValtozott: () => Promise<void>;
}) {
  const [ujNyitva, setUjNyitva] = useState(false);
  const [uj, setUj] = useState('');
  const { busy, error, run } = useAkcio();
  const inputId = useId();
  const aktiv = kulcsszavak.filter((k) => k.allapot !== 'elutasitva');
  const elutasitott = kulcsszavak.filter((k) => k.allapot === 'elutasitva');

  async function hozzaad(e: FormEvent) {
    e.preventDefault();
    if (!uj.trim()) return;
    const ok = await run(async () => {
      await api.addKulcsszo(felvetelId, uj);
      await onValtozott();
      return true;
    });
    if (ok) {
      setUj('');
      setUjNyitva(false);
    }
  }

  const dont = (k: Kulcsszo, elfogad: boolean) =>
    run(async () => {
      await api.dontKulcsszo(felvetelId, k.id, elfogad);
      await onValtozott();
    });

  return (
    <section className="kwpanel" aria-label="Kulcsszavak">
      <div className="row-baseline">
        <h3 className="h2 h2--sm">Kulcsszavak</h3>
        <span className="meta">kiemelve az átiratban · az AI-javaslat a döntésedig javaslat</span>
      </div>
      <ul className="chips">
        {aktiv.map((k) => (
          <li key={k.id} className={`chip chip--${k.forras}${k.allapot === 'javaslat' ? ' chip--javaslat' : ''}`}>
            <span title={k.forras === 'ai' ? `AI-javaslat (${k.modell ?? '?'})` : 'Orvos adta hozzá'}>
              {k.forras === 'ai' && <span className="chip__tag">AI</span>}
              {k.kifejezes}
            </span>
            {k.allapot === 'javaslat' && (
              <>
                <button
                  type="button"
                  className="chip__btn"
                  aria-label={`„${k.kifejezes}” elfogadása`}
                  onClick={() => void dont(k, true)}
                  disabled={busy}
                >
                  <Icon name="check" size={12} strokeWidth={2.8} />
                </button>
                <button
                  type="button"
                  className="chip__btn"
                  aria-label={`„${k.kifejezes}” elutasítása`}
                  onClick={() => void dont(k, false)}
                  disabled={busy}
                >
                  <Icon name="x" size={12} strokeWidth={2.8} />
                </button>
              </>
            )}
          </li>
        ))}
        <li>
          {ujNyitva ? (
            <form className="chip chip--form" onSubmit={hozzaad}>
              <label htmlFor={inputId} className="sr-only">
                Új kulcsszó
              </label>
              <input
                id={inputId}
                className="input input--chip"
                autoFocus
                maxLength={200}
                value={uj}
                placeholder="pl. mellkasi fájdalom"
                onChange={(e) => setUj(e.target.value)}
                onKeyDown={(e) => e.key === 'Escape' && setUjNyitva(false)}
              />
              <button type="submit" className="chip__btn" aria-label="Kulcsszó mentése" disabled={busy || !uj.trim()}>
                <Icon name="check" size={12} strokeWidth={2.8} />
              </button>
              <button type="button" className="chip__btn" aria-label="Mégse" onClick={() => setUjNyitva(false)}>
                <Icon name="x" size={12} strokeWidth={2.8} />
              </button>
            </form>
          ) : (
            <button type="button" className="chip chip--add" onClick={() => setUjNyitva(true)} aria-label="Kulcsszó hozzáadása">
              <Icon name="plus" size={14} strokeWidth={2.6} />
            </button>
          )}
        </li>
      </ul>
      {elutasitott.length > 0 && (
        <p className="meta">
          Elutasított AI-javaslat: {elutasitott.map((k) => k.kifejezes).join(', ')}
        </p>
      )}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}

/**
 * Összefoglaló a teljes átiratból és a (nem elutasított) kulcsszavakból. A felvétel kész
 * állapotakor magától elkészül; kulcsszó-módosítás után gombbal újragenerálható. Mondatonként
 * a hivatkozott megszólalásokra ugrik (turnId). Modell-kimenet: az orvos ellenőrzi.
 */
export function FelvetelOsszefoglaloPanel({
  felvetelId,
  kulcsszavak,
  onUgras,
}: {
  felvetelId: number;
  kulcsszavak: Kulcsszo[];
  onUgras: (turnIds: number[]) => void;
}) {
  const lista = useAsync(() => api.listFelvetelOsszefoglalok(felvetelId), [felvetelId]);
  const { busy, error, run } = useAkcio();
  const legfrissebb: FelvetelOsszefoglalo | undefined = lista.state.status === 'ok' ? lista.state.data[0] : undefined;
  const keszul = legfrissebb?.allapot === 'feldolgozas';

  useEffect(() => {
    if (!keszul) return;
    const t = window.setInterval(() => void lista.refresh().catch(() => undefined), 2500);
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keszul]);

  // A legutóbbi összefoglaló óta változott-e a kulcsszavak köre (akkor érdemes újragenerálni)
  const most = kulcsszavak
    .filter((k) => k.allapot !== 'elutasitva')
    .map((k) => k.kifejezes.toLowerCase())
    .sort()
    .join('|');
  const akkor = (legfrissebb?.kulcsszavak ?? []).map((k) => k.toLowerCase()).sort().join('|');
  const elavult = legfrissebb?.allapot === 'kesz' && most !== akkor;

  const ujra = () =>
    run(async () => {
      await api.kerFelvetelOsszefoglalo(felvetelId);
      await lista.refresh();
    });

  return (
    <section className="kwpanel" aria-label="Összefoglaló">
      <div className="row-baseline">
        <h3 className="h2 h2--sm">Összefoglaló</h3>
        <span className="tag tag--modell">AI · ellenőrizendő</span>
        <button type="button" className="btn btn--sm push-right" onClick={() => void ujra()} disabled={busy || keszul}>
          <Icon name="spark" size={14} />
          {legfrissebb ? 'Újragenerálás' : 'Összefoglaló készítése'}
        </button>
      </div>
      {lista.state.status === 'loading' && <Loading what="Összefoglaló" />}
      {lista.state.status === 'error' && <ErrorState error={lista.state.error} onRetry={lista.reload} />}
      {lista.state.status === 'ok' && !legfrissebb && <p className="meta">Még nem készült összefoglaló.</p>}
      {keszul && (
        <p className="meta" role="status">
          <span className="stat stat--feldolgozas">Készül</span> a teljes átiratból és{' '}
          {legfrissebb.kulcsszavak.length} kulcsszóból…
        </p>
      )}
      {legfrissebb?.allapot === 'hiba' && (
        <p className="form-error" role="alert">
          {legfrissebb.hiba}
        </p>
      )}
      {legfrissebb?.allapot === 'kesz' && (
        <>
          {elavult && (
            <p className="meta" role="note">
              <Icon name="info" size={13} /> A kulcsszavak változtak az összefoglaló óta — generáld újra, hogy
              figyelembe vegye őket.
            </p>
          )}
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
          {legfrissebb.figyelmeztetesek.length > 0 && (
            <ul className="itemlist">
              {legfrissebb.figyelmeztetesek.map((w, i) => (
                <li key={i} className="turn__note">
                  <Icon name="alert" size={13} /> {w}
                </li>
              ))}
            </ul>
          )}
          <p className="meta mono">
            {legfrissebb.modell} · {formatDatumIdo(legfrissebb.kerve)} · kulcsszavak:{' '}
            {legfrissebb.kulcsszavak.length > 0 ? legfrissebb.kulcsszavak.join(', ') : '—'}
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
