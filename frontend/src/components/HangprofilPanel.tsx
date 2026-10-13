import { useEffect, useRef, useState } from 'react';
import { api } from '../api';
import type { Hangprofil } from '../api/types';
import { formatDatumIdo } from '../lib/format';
import {
  felvetelInditasa,
  felvetelTamogatott,
  mikrofonElengedese,
  mikrofonMegnyitasa,
  mikrofonNeve,
  type Felvevo,
} from '../lib/hangfelvetel';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { Icon } from './Icon';
import { ErrorState, Loading } from './shell';

/**
 * Hangprofil: az orvos a böngészőben felolvas néhány mondatot, a Python hangszolgáltatás ebből
 * számolja a hanglenyomatot (POST /voice-profiles, 202, majd állapotkövetés). Felhasználónként
 * legfeljebb egy aktív profil; új profilnál a régi automatikusan inaktív lesz, de az előzményben
 * megmarad. Használható (aktív, hanglenyomattal rendelkező) profil nélkül nem indítható felvétel.
 */
export function HangprofilPanel({ felhasznaloId, szerkesztheto }: { felhasznaloId: number; szerkesztheto: boolean }) {
  const aktiv = useAsync(() => api.getAktivHangprofil(felhasznaloId), [felhasznaloId]);
  const elozmeny = useAsync(() => api.listHangprofilok(felhasznaloId), [felhasznaloId]);
  const [ujNyitva, setUjNyitva] = useState(false);

  const frissit = async () => {
    await Promise.all([aktiv.refresh(), elozmeny.refresh()]);
  };

  // A legutóbbi kísérlet: ha még készül, követjük; ha elbukott, megmutatjuk az okát
  const legutobbi = elozmeny.state.status === 'ok' ? elozmeny.state.data[0] : undefined;
  const keszul = legutobbi?.allapot === 'feldolgozas';
  useEffect(() => {
    if (!keszul) return;
    const t = window.setInterval(() => void frissit().catch(() => undefined), 2000);
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keszul]);

  return (
    <section className="subsection" aria-label="Hangprofil">
      <div className="row-baseline">
        <h2 className="h2 h2--sm">Hangprofil</h2>
        <span className="meta">a beszélő-azonosításhoz (orvos hangmintája)</span>
        {szerkesztheto && !ujNyitva && !keszul && (
          <button type="button" className="btn btn--sm push-right" onClick={() => setUjNyitva(true)}>
            <Icon name="mic" size={14} />
            {aktiv.state.status === 'ok' && aktiv.state.data ? 'Új hangminta felvétele' : 'Hangminta felvétele'}
          </button>
        )}
      </div>

      {ujNyitva && (
        <HangmintaFelvetel
          felhasznaloId={felhasznaloId}
          onDone={async () => {
            setUjNyitva(false);
            await frissit();
          }}
          onCancel={() => setUjNyitva(false)}
        />
      )}

      {keszul && (
        <p className="meta" role="status">
          <span className="stat stat--feldolgozas">Feldolgozás alatt</span> A hanglenyomat készül a felolvasott
          mondatokból…
        </p>
      )}
      {legutobbi?.allapot === 'hiba' && (
        <p className="form-error" role="alert">
          A legutóbbi hangminta nem használható ({formatDatumIdo(legutobbi.rogzitve)}): {legutobbi.hiba}
        </p>
      )}

      {aktiv.state.status === 'loading' && <Loading what="Aktív hangprofil" />}
      {aktiv.state.status === 'error' && <ErrorState error={aktiv.state.error} onRetry={aktiv.reload} />}
      {aktiv.state.status === 'ok' &&
        (aktiv.state.data ? (
          <ProfilAdatok p={aktiv.state.data} />
        ) : (
          <p className="meta">Nincs aktív hangprofil — amíg nincs, egyik betegnél sem indítható hangfelvétel.</p>
        ))}

      {elozmeny.state.status === 'ok' && elozmeny.state.data.some((p) => !p.aktiv && p.allapot === 'kesz') && (
        <details className="subsection__more">
          <summary>
            Korábbi profilok ({elozmeny.state.data.filter((p) => !p.aktiv && p.allapot === 'kesz').length})
          </summary>
          <ul className="itemlist">
            {elozmeny.state.data
              .filter((p) => !p.aktiv && p.allapot === 'kesz')
              .map((p) => (
                <li key={p.id} className="item item--off">
                  <div className="item__row">
                    <span className="stat stat--kesz">Inaktív</span>
                    <span className="meta">
                      rögzítve {formatDatumIdo(p.rogzitve)} · {p.embeddingModell ?? '—'}
                    </span>
                  </div>
                </li>
              ))}
          </ul>
        </details>
      )}
      {elozmeny.state.status === 'error' && <ErrorState error={elozmeny.state.error} onRetry={elozmeny.reload} />}
    </section>
  );
}

function ProfilAdatok({ p }: { p: Hangprofil }) {
  return (
    <dl className="deflist">
      <dt>Állapot</dt>
      <dd>
        <span className="stat stat--nyitott">Aktív</span>
        {!p.hasznalhato && (
          <span className="form-error">
            {' '}
            Régi, kézzel rögzített profil hanglenyomat nélkül — vegyél fel új hangmintát.
          </span>
        )}
      </dd>
      <dt>Beágyazó modell</dt>
      <dd className="mono">{p.embeddingModell ?? '—'}</dd>
      <dt>Nettó beszéd</dt>
      <dd className="mono">
        {p.nettoBeszedMp !== null ? `${p.nettoBeszedMp} mp` : '—'}
        {p.mondatok !== null && ` · ${p.mondatok} mondat`}
      </dd>
      <dt>Mikrofon</dt>
      <dd>{p.mikrofon ?? '—'}</dd>
      <dt>Rögzítve</dt>
      <dd className="mono">{formatDatumIdo(p.rogzitve)}</dd>
      {p.figyelmeztetesek.length > 0 && (
        <>
          <dt>Megjegyzés</dt>
          <dd>
            <ul className="itemlist">
              {p.figyelmeztetesek.map((w) => (
                <li key={w} className="meta">
                  {w}
                </li>
              ))}
            </ul>
          </dd>
        </>
      )}
    </dl>
  );
}

// A prototípus (ai-service/voice/hangprofil.py) mondatai: sokféle magyar hang (ö/ő/ü/ű, gy/ny/ty,
// sz/zs/cs), és a munka közbeni beszédhez hasonló stílus (kérdés, utasítás, számok diktálása).
// A hanglenyomat szövegfüggetlen: a tartalom nem számít, csak az elég nettó beszéd.
const MONDATOK = [
  'Jó napot kívánok, kérem, mondja el, mi történt önnel.',
  'Mióta tart a fájdalom, hová sugárzik, és érez-e zsibbadást a kezében vagy a lábában?',
  'Szed rendszeresen gyógyszert, például vérnyomáscsökkentőt, vérhígítót vagy inzulint?',
  'Most meghallgatom a szívét és a tüdejét. Vegyen mély levegőt, és tartsa bent egy pillanatig.',
  'A bal kamra ejekciós frakciója ötvenöt százalék, regionális falmozgászavar nem látható.',
  'Az E per e vessző arány tizenkettő, a tricuspidalis regurgitáció sebessége két egész nyolc méter per szekundum.',
  'Hány éves korában műtötték, és volt-e azóta szövődménye vagy gyógyszerallergiás reakciója?',
  'Köszönöm a türelmét. Az eredményeket egy óra múlva megbeszéljük a hozzátartozójával együtt.',
];

// Gyors, kliensoldali szűrés (a valódi ellenőrzés a Pythonban fut, VAD-dal)
const MIN_HOSSZ_MP = 2;
const MAX_HOSSZ_MP = 25;
const HALK_CSUCS = 0.03;
const CSEND_MP = 2;

type Lepes = { tipus: 'kezdes' } | { tipus: 'csend' } | { tipus: 'mondat'; index: number } | { tipus: 'kesz' };

function HangmintaFelvetel({
  felhasznaloId,
  onDone,
  onCancel,
}: {
  felhasznaloId: number;
  onDone: () => Promise<void>;
  onCancel: () => void;
}) {
  const [lepes, setLepes] = useState<Lepes>({ tipus: 'kezdes' });
  const [mondatok, setMondatok] = useState<Blob[]>([]);
  const [csend, setCsend] = useState<Blob | null>(null);
  const [fut, setFut] = useState(false);
  const [szint, setSzint] = useState(0);
  const [hossz, setHossz] = useState(0);
  const [hiba, setHiba] = useState<string | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const felvevoRef = useRef<Felvevo | null>(null);
  const mikrofonRef = useRef<string | null>(null);
  const { busy, error, run } = useAkcio();

  // Kilépéskor (Mégse, oldalváltás) a mikrofon elengedése
  useEffect(
    () => () => {
      felvevoRef.current?.megszakit();
      mikrofonElengedese(streamRef.current);
    },
    [],
  );

  useEffect(() => {
    if (!fut) return;
    const t = window.setInterval(() => {
      const f = felvevoRef.current;
      if (!f) return;
      setSzint(f.szint());
      setHossz(f.hossz());
      if (f.hossz() > MAX_HOSSZ_MP) void leallit();
    }, 100);
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fut]);

  if (!felvetelTamogatott()) {
    return <p className="form-error">Ez a böngésző nem támogatja a hangfelvételt.</p>;
  }

  async function kezdes() {
    setHiba(null);
    try {
      streamRef.current = await mikrofonMegnyitasa();
      mikrofonRef.current = await mikrofonNeve(streamRef.current);
    } catch (e) {
      setHiba((e as Error).message);
      return;
    }
    // Háttérzaj: 2 mp csend, a jel/zaj arány becsléséhez (hangprofil.py: measure_noise)
    setLepes({ tipus: 'csend' });
    const f = felvetelInditasa(streamRef.current);
    felvevoRef.current = f;
    setFut(true);
    window.setTimeout(async () => {
      const blob = await f.stop();
      felvevoRef.current = null;
      setFut(false);
      setCsend(blob);
      setLepes({ tipus: 'mondat', index: 0 });
    }, CSEND_MP * 1000);
  }

  function inditMondat() {
    if (!streamRef.current) return;
    setHiba(null);
    felvevoRef.current = felvetelInditasa(streamRef.current);
    setFut(true);
  }

  async function leallit() {
    const f = felvevoRef.current;
    if (!f || lepes.tipus !== 'mondat') return;
    felvevoRef.current = null;
    const dur = f.hossz();
    const peak = f.csucs();
    const blob = await f.stop();
    setFut(false);
    setSzint(0);
    if (dur < MIN_HOSSZ_MP) {
      setHiba(`Túl rövid (${dur.toFixed(1)} mp) — olvasd fel a teljes mondatot.`);
      return;
    }
    if (peak < HALK_CSUCS) {
      setHiba('Nagyon halk — beszélj közelebb a mikrofonhoz, vagy emeld a mikrofon szintjét.');
      return;
    }
    const kovetkezo = [...mondatok];
    kovetkezo[lepes.index] = blob;
    setMondatok(kovetkezo);
    setLepes(lepes.index + 1 < MONDATOK.length ? { tipus: 'mondat', index: lepes.index + 1 } : { tipus: 'kesz' });
  }

  async function mentes() {
    const p = await run(() => api.createHangprofil({ felhasznaloId, mondatok, csend, mikrofon: mikrofonRef.current }));
    if (p) {
      mikrofonElengedese(streamRef.current);
      streamRef.current = null;
      await onDone();
    }
  }

  return (
    <div className="tabform tabform--inset" aria-label="Hangminta felvétele">
      <strong>Hangminta felvétele</strong>
      <span className="meta">
        {MONDATOK.length} rövid mondatot olvasol fel, egyenként. Ugyanazzal a mikrofonnal és távolságból, ahogy munka
        közben is fogod használni; természetes tempóban és hangerővel. Kész profilnál a mostani aktív inaktív lesz.
      </span>

      {lepes.tipus === 'kezdes' && (
        <div className="actions">
          <button type="button" className="btn btn--primary" onClick={kezdes}>
            <Icon name="mic" size={16} />
            Mikrofon bekapcsolása és kezdés
          </button>
          <button type="button" className="btn" onClick={onCancel}>
            Mégse
          </button>
        </div>
      )}

      {lepes.tipus === 'csend' && (
        <p className="meta" role="status">
          Háttérzaj mérése ({CSEND_MP} mp) — kérlek, most maradj csendben…
        </p>
      )}

      {lepes.tipus === 'mondat' && (
        <>
          <p className="meta">
            {lepes.index + 1}. mondat / {MONDATOK.length}
          </p>
          <blockquote className="quote">„{MONDATOK[lepes.index]}”</blockquote>
          <SzintMero szint={fut ? szint : 0} />
          <div className="actions">
            {fut ? (
              <button type="button" className="btn btn--primary" onClick={() => void leallit()}>
                <Icon name="stop" size={16} />
                Kész ({hossz.toFixed(0)} mp)
              </button>
            ) : (
              <button type="button" className="btn btn--primary" onClick={inditMondat}>
                <Icon name="mic" size={16} />
                Felolvasás indítása
              </button>
            )}
            <button type="button" className="btn" onClick={onCancel} disabled={fut}>
              Mégse
            </button>
          </div>
        </>
      )}

      {lepes.tipus === 'kesz' && (
        <>
          <p className="meta">
            <Icon name="check" size={14} /> Mind a {MONDATOK.length} mondat felvéve. A mentés után a szerver kiszűri a
            szüneteket és a zajt, majd elkészíti a hanglenyomatot.
          </p>
          <div className="actions">
            <button type="button" className="btn btn--primary" onClick={mentes} disabled={busy}>
              <Icon name="upload" size={16} />
              {busy ? 'Feltöltés…' : 'Hangprofil mentése'}
            </button>
            <button
              type="button"
              className="btn"
              onClick={() => {
                setMondatok([]);
                setLepes({ tipus: 'mondat', index: 0 });
              }}
              disabled={busy}
            >
              Újrakezdés
            </button>
            <button type="button" className="btn" onClick={onCancel} disabled={busy}>
              Mégse
            </button>
          </div>
        </>
      )}

      {hiba && (
        <p className="form-error" role="alert">
          {hiba}
        </p>
      )}
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

export function SzintMero({ szint }: { szint: number }) {
  // RMS → kb. 0–1 skála (beszédnél jellemzően 0,02–0,2)
  const arany = Math.min(1, szint * 8);
  return (
    <div
      className="levelmeter"
      role="meter"
      aria-label="Hangerő"
      aria-valuemin={0}
      aria-valuemax={1}
      aria-valuenow={arany}
    >
      <div className="levelmeter__bar" style={{ width: `${Math.round(arany * 100)}%` }} />
    </div>
  );
}
