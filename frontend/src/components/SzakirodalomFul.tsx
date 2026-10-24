import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api';
import type { Cikk, CikkOldal, CikkRelevanciaSzuro, Kutatas, KutatasForras, KutatasiOsszefoglalo } from '../api/types';
import { formatDatumIdo } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { usePatient } from '../patient/PatientContext';
import { Icon } from './Icon';
import { KeresoFogalmak } from './KeresoFogalmak';
import { Segmented } from './Segmented';
import { ErrorState, Loading } from './shell';

/**
 * Szakirodalmi kutatás (docs/literature-research.md). A beteg állapotának összefoglalóját a helyi
 * modell javasolja, az orvos átírja; a hálózatra (PubMed) CSAK a látható, szerkeszthető
 * keresőkifejezés megy ki. A cikkek összefoglalója és relevanciája modell-kimenet — a döntés az orvosé.
 */
type Mod = 'uj' | 'korabbi' | 'konyvjelzo';

export function SzakirodalomFul() {
  const [params, setParams] = useSearchParams();
  const kutatasId = Number(params.get('kutatas')) || null;
  const [mod, setMod] = useState<Mod>('uj');

  const nyit = (id: number | null) =>
    setParams((p) => {
      const n = new URLSearchParams(p);
      n.set('nezet', 'szakirodalom');
      if (id) n.set('kutatas', String(id));
      else n.delete('kutatas');
      return n;
    });

  return (
    <div className="tabbody kutatas">
      <div className="row-baseline kutatas__top">
        <Icon name="book" size={18} />
        <h2 className="h2 h2--sm">Szakirodalom</h2>
        <div className="push-right">
          <Segmented
            label="Szakirodalom nézet"
            value={kutatasId ? 'korabbi' : mod}
            onChange={(v) => {
              setMod(v);
              nyit(null);
            }}
            options={[
              ['uj', 'Új kutatás'],
              ['korabbi', 'Korábbiak'],
              ['konyvjelzo', '★ Könyvjelzők'],
            ]}
          />
        </div>
      </div>
      {kutatasId ? (
        <KutatasNezet key={kutatasId} id={kutatasId} />
      ) : mod === 'uj' ? (
        <UjKutatas onIndit={nyit} />
      ) : mod === 'korabbi' ? (
        <Korabbiak onNyit={nyit} />
      ) : (
        <Konyvjelzok />
      )}
    </div>
  );
}

// ---------------------------------------------------------------- új kutatás

function UjKutatas({ onIndit }: { onIndit: (id: number) => void }) {
  const { patient } = usePatient();
  const { state, reload, refresh } = useAsync(() => api.listKutatasiOsszefoglalok(patient.id), [patient.id]);
  const keszites = useAkcio();
  const mentes = useAkcio();
  const inditas = useAkcio();
  const utolso = state.status === 'ok' ? state.data[0] : undefined;
  const keszul = utolso?.allapot === 'keszul';

  const [szoveg, setSzoveg] = useState('');
  const [kifejezes, setKifejezes] = useState('');
  const [keres, setKeres] = useState('');
  const [forrasok, setForrasok] = useState<KutatasForras[]>(['PUBMED', 'COCHRANE', 'EUROPE_PMC']);
  const [max, setMax] = useState(50);
  // az összefoglaló betöltésekor (és új összefoglaló érkezésekor) a mezők felveszik az értékét
  const betoltott = useRef<string | null>(null);
  useEffect(() => {
    if (!utolso || utolso.allapot !== 'kesz') return;
    const kulcs = `${utolso.id}:${utolso.szerkesztve ?? ''}`;
    if (betoltott.current === kulcs) return;
    betoltott.current = kulcs;
    setSzoveg(utolso.szoveg ?? '');
    setKifejezes(utolso.kifejezes ?? '');
  }, [utolso]);

  useEffect(() => {
    if (!keszul) return;
    const t = setInterval(() => refresh().catch(() => {}), 5000);
    return () => clearInterval(t);
  }, [keszul, refresh]);

  const kesz = utolso?.allapot === 'kesz' ? utolso : undefined;
  const modositott =
    !!kesz && (szoveg.trim() !== (kesz.szoveg ?? '').trim() || kifejezes.trim() !== (kesz.kifejezes ?? '').trim());

  async function indit() {
    // a szerkesztés mentődik (az AI eredetije mellé), hogy a kutatás alapja visszakereshető legyen
    if (modositott && kesz) {
      const ok = await mentes.run(() => api.szerkesztKutatasiOsszefoglalot(kesz.id, szoveg, kifejezes));
      if (!ok) return;
    }
    const k = await inditas.run(() =>
      api.inditKutatast({
        patientId: patient.id,
        osszefoglaloId: kesz?.id ?? null,
        kontextus: szoveg,
        keres,
        kifejezes,
        forrasok,
        maxTalalat: max,
      }),
    );
    if (k) onIndit(k.id);
  }

  const toggleForras = (f: KutatasForras) =>
    setForrasok((fs) => (fs.includes(f) ? fs.filter((x) => x !== f) : [...fs, f]));

  return (
    <div className="kutatas__uj">
      <article className="klinstatus__card" aria-label="A beteg állapota a kutatáshoz">
        <div className="row-baseline klinstatus__head">
          <h3 className="h2 h2--sm">A beteg állapota a kutatáshoz</h3>
          {kesz && (
            <span className={kesz.szerkesztett ? 'tag tag--forras' : 'klinstatus__badge'}>
              {kesz.szerkesztett ? (
                'Orvos szerkesztette'
              ) : (
                <>
                  <Icon name="spark" size={12} /> AI-javaslat
                </>
              )}
            </span>
          )}
          <button
            type="button"
            className="btn btn--sm push-right"
            onClick={async () => {
              if (await keszites.run(() => api.keszitKutatasiOsszefoglalot(patient.id))) await refresh();
            }}
            disabled={keszites.busy || keszul || state.status !== 'ok'}
          >
            <Icon name="spark" size={14} />
            {keszul ? 'Készül…' : utolso ? 'Új AI-összefoglaló' : 'AI-összefoglaló készítése'}
          </button>
        </div>
        {keszites.error && (
          <p className="form-error" role="alert">
            {keszites.error}
          </p>
        )}
        {state.status === 'loading' && <Loading what="Összefoglaló" />}
        {state.status === 'error' && <ErrorState error={state.error} onRetry={reload} />}
        {keszul && (
          <p className="meta" role="status">
            Az összefoglaló készül ({formatDatumIdo(utolso.kerve)} óta) a helyi modellel — a nagy modell néhány percig
            dolgozik. Addig saját szöveggel is indíthatsz kutatást.
          </p>
        )}
        {utolso?.allapot === 'hiba' && (
          <p className="form-error" role="alert">
            Az összefoglaló nem készült el: {utolso.hiba}
          </p>
        )}
        <div className="field">
          <label htmlFor="kut-szoveg">
            Összefoglaló (a modell ehhez méri a cikkek relevanciáját — nem megy ki a hálózatra)
          </label>
          <textarea
            id="kut-szoveg"
            className="textarea"
            rows={6}
            value={szoveg}
            onChange={(e) => setSzoveg(e.target.value)}
            placeholder="Pl. 72 éves férfi, csökkent ejekciós frakciójú szívelégtelenség, pitvarfibrilláció…"
          />
        </div>
        {kesz && kesz.figyelmeztetesek.length > 0 && (
          <ul className="klinstatus__warn" aria-label="Ellenőrizendő">
            {kesz.figyelmeztetesek.map((w, i) => (
              <li key={i}>
                <Icon name="alert" size={12} /> {w}
              </li>
            ))}
          </ul>
        )}
        {kesz?.szerkesztett && kesz.aiSzoveg && (
          <details className="meta">
            <summary>Az AI eredeti összefoglalója</summary>
            <p className="klinstatus__text">{kesz.aiSzoveg}</p>
            {kesz.aiKifejezes && <p className="mono">{kesz.aiKifejezes}</p>}
          </details>
        )}
        <OsszefoglaloMeta o={kesz} />
      </article>

      <div className="field">
        <label htmlFor="kut-keres">Kiegészítő kérés a modellnek (nem kötelező, nem megy ki a hálózatra)</label>
        <textarea
          id="kut-keres"
          className="textarea"
          rows={2}
          maxLength={2000}
          value={keres}
          onChange={(e) => setKeres(e.target.value)}
          placeholder="Pl. Az SGLT2-gátló bevezetése érdekel csökkent vesefunkció mellett."
        />
      </div>

      <div className="field">
        <label htmlFor="kut-kifejezes">Keresőkifejezés (angol, PubMed-szintaxis)</label>
        <input
          id="kut-kifejezes"
          className="input mono"
          value={kifejezes}
          maxLength={1000}
          onChange={(e) => setKifejezes(e.target.value)}
          placeholder="Pl. (Heart Failure[MeSH Terms] OR Heart Failure[tiab]) AND SGLT2 inhibitors[tiab]"
        />
        <KeresoFogalmak kifejezes={kifejezes} onChange={setKifejezes} />
        <p className="meta kutatas__kimeno">
          <Icon name="lock" size={12} /> Csak ez a kifejezés megy ki az internetre (PubMed, Europe PMC, iCite).
          Betegazonosító (név, TAJ, dátum, életkor) nem lehet benne — a rendszer ellenőrzi, és ilyennél nem indul a
          keresés.
        </p>
      </div>

      <div className="row-baseline kutatas__opciok">
        <fieldset className="kutatas__forrasok">
          <legend className="field__legend">Források</legend>
          <label>
            <input type="checkbox" checked={forrasok.includes('PUBMED')} onChange={() => toggleForras('PUBMED')} />{' '}
            PubMed
          </label>
          <label>
            <input type="checkbox" checked={forrasok.includes('COCHRANE')} onChange={() => toggleForras('COCHRANE')} />{' '}
            Cochrane-áttekintések
          </label>
          <label>
            <input
              type="checkbox"
              checked={forrasok.includes('EUROPE_PMC')}
              onChange={() => toggleForras('EUROPE_PMC')}
            />{' '}
            Europe PMC
          </label>
        </fieldset>
        <label className="field">
          <span className="field__legend">Legfeljebb</span>
          <select className="input input--sm" value={max} onChange={(e) => setMax(Number(e.target.value))}>
            {[20, 50, 100].map((n) => (
              <option key={n} value={n}>
                {n} cikk
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="btn btn--primary push-right"
          onClick={() => void indit()}
          disabled={inditas.busy || mentes.busy || !szoveg.trim() || !kifejezes.trim() || forrasok.length === 0}
        >
          <Icon name="search" size={16} />
          Kutatás indítása
        </button>
      </div>
      {(mentes.error || inditas.error) && (
        <p className="form-error" role="alert">
          {mentes.error ?? inditas.error}
        </p>
      )}
    </div>
  );
}

function OsszefoglaloMeta({ o }: { o?: KutatasiOsszefoglalo }) {
  if (!o) return null;
  return (
    <p className="meta">
      Készült {formatDatumIdo(o.kerve)}
      {o.modell && (
        <>
          {' '}
          · <span className="mono">{o.modell}</span>
        </>
      )}
      {o.szerkesztve && <> · szerkesztve {formatDatumIdo(o.szerkesztve)}</>}
    </p>
  );
}

// ---------------------------------------------------------------- egy kutatás

type Szuro = 'ACTIVE' | 'BOOKMARKED' | 'EXCLUDED';

function KutatasNezet({ id }: { id: number }) {
  const kutatas = useAsync(() => api.getKutatas(id), [id]);
  const { refresh } = kutatas;
  const keres =
    kutatas.state.status === 'ok' &&
    (kutatas.state.data.allapot === 'keres' || kutatas.state.data.rangsor.allapot === 'keszul');

  // keresés és rangsorolás közben 2 mp-enként: az állapotüzenet (PubMed, letöltés, előszűrés) a Pythonból jön
  useEffect(() => {
    if (!keres) return;
    const t = setInterval(() => refresh().catch(() => {}), 2000);
    return () => clearInterval(t);
  }, [keres, refresh]);

  if (kutatas.state.status === 'loading') return <Loading what="Kutatás" />;
  if (kutatas.state.status === 'error') return <ErrorState error={kutatas.state.error} onRetry={kutatas.reload} />;
  const kk = kutatas.state.data;
  return (
    <div className="kutatas__nezet">
      <KutatasFej k={kk} />
      {kk.allapot === 'keres' && (
        <p className="kutatas__progress" role="status" aria-live="polite">
          <span className="kutatas__spinner" aria-hidden="true" />
          {kk.allapotUzenet ?? 'Keresés…'}
        </p>
      )}
      {kk.allapot === 'hiba' && (
        <p className="form-error" role="alert">
          A keresés nem sikerült: {kk.hiba}
        </p>
      )}
      {kk.allapot === 'kesz' && (
        // a rangsor elkészültekor újra betölt (az 1. oldalra): a sorrend ekkor változik, közben nem
        <Talalatok key={kk.rangsor.allapot} k={kk} onValtozas={() => void refresh().catch(() => {})} />
      )}
    </div>
  );
}

function KutatasFej({ k }: { k: Kutatas }) {
  return (
    <section className="kutatas__fej" aria-label="A kutatás adatai">
      <p className="mono kutatas__kifejezes">{k.kifejezes}</p>
      <p className="meta">
        {formatDatumIdo(k.inditva)} · {k.forrasok.map((f) => FORRAS_FELIRAT[f]).join(' + ')}
        {k.osszesTalalat !== null && <> · {k.osszesTalalat.toLocaleString('hu-HU')} találat a forrásban</>}
        {k.allapot === 'kesz' && (
          <>
            {' '}
            · {k.cikkek.osszes} letöltve, {k.cikkek.ertekelt} értékelve
            {k.cikkek.konyvjelzo > 0 && <>, ★ {k.cikkek.konyvjelzo}</>}
            {k.cikkek.kivett > 0 && <>, {k.cikkek.kivett} kivéve</>}
          </>
        )}
      </p>
      <details className="meta">
        <summary>Mihez mérte a modell a relevanciát</summary>
        <p className="klinstatus__text">{k.kontextus}</p>
        {k.keres && <p>Kiegészítő kérés: {k.keres}</p>}
      </details>
    </section>
  );
}

function Talalatok({ k, onValtozas }: { k: Kutatas; onValtozas: () => void }) {
  const [szuro, setSzuro] = useState<Szuro>('ACTIVE');
  const [relevancia, setRelevancia] = useState<CikkRelevanciaSzuro>('ALL');
  const [oldal, setOldal] = useState(0);
  // mindig a látható relevancia szerint (a felhasználó döntése: a forrás-sorrend váltó nem kell): ha egy cikk
  // értékelése magasabb (vagy alacsonyabb) relevanciát ad, a következő frissítéskor a helyére kerül
  const lista = useAsync(
    () => api.getCikkOldal(k.id, oldal, szuro, 'AI', relevancia),
    [k.id, oldal, szuro, relevancia],
  );
  const { refresh } = lista;
  const ertekeles = useAkcio();
  const { run } = ertekeles;
  const ujraRangsor = useAkcio();
  const adat: CikkOldal | undefined = lista.state.status === 'ok' ? lista.state.data : undefined;
  const varakozik = !!adat?.cikkek.some((c) => c.ertekeles.allapot === 'keszul');
  const kert = useRef(new Set<number>());
  const r = k.rangsor;
  const rangsorol = r.allapot === 'keszul';

  // a megnyitott oldal még nem értékelt cikkei mennek a modellhez (nem mind előre). Rangsorolás közben
  // nem: utána a szerver a rangsor szerinti első oldalt értékeli, a mostani oldal addig átrendeződik.
  useEffect(() => {
    if (!adat || rangsorol) return;
    const ids = adat.cikkek
      .filter((c) => !c.kivett && c.ertekeles.allapot === 'nincs' && !kert.current.has(c.id))
      .map((c) => c.id);
    if (ids.length === 0) return;
    ids.forEach((i) => kert.current.add(i));
    void run(() => api.ertekelCikkeket(k.id, ids)).then(() => refresh().catch(() => {}));
  }, [adat, rangsorol, run, k.id, refresh]);

  useEffect(() => {
    if (!varakozik) return;
    const t = setInterval(() => refresh().catch(() => {}), 3000);
    return () => clearInterval(t);
  }, [varakozik, refresh]);

  async function modosit(c: Cikk, m: { kivett?: boolean; konyvjelzo?: boolean }) {
    await api.modositCikket(k.id, c.id, m);
    await refresh();
    onValtozas();
  }

  return (
    <section className="kutatas__talalatok" aria-label="Találatok">
      <div className="row-baseline">
        <Segmented
          label="Találatok szűrése"
          value={szuro}
          onChange={(v) => {
            setSzuro(v);
            setOldal(0);
          }}
          options={[
            ['ACTIVE', 'Találatok'],
            ['BOOKMARKED', '★ Könyvjelzett'],
            ['EXCLUDED', 'Kivett'],
          ]}
        />
        <Segmented
          label="Relevancia"
          value={relevancia}
          onChange={(v) => {
            setRelevancia(v);
            setOldal(0);
          }}
          options={[
            ['ALL', 'Összes'],
            ['HIGH', 'Erősen'],
            ['MEDIUM', 'Részben'],
            ['LOW', 'Kevéssé'],
            ['NONE', 'Besorolatlan'],
          ]}
        />
        {varakozik && (
          <span className="meta push-right" role="status">
            <span className="kutatas__spinner" aria-hidden="true" /> AI-értékelés folyamatban…
          </span>
        )}
      </div>
      {rangsorol && (
        <p className="kutatas__progress" role="status" aria-live="polite">
          <span className="kutatas__spinner" aria-hidden="true" />
          {r.uzenet ?? 'AI-rangsor…'} — a helyi modell az összes találatot átnézi, és kiemeli a legrelevánsabbat. Addig a
          forrás sorrendje látszik.
        </p>
      )}
      {r.allapot === 'hiba' && (
        <p className="form-error" role="alert">
          Az AI-rangsor nem készült el: {r.hiba}
        </p>
      )}
      {(r.allapot === 'kesz' || r.allapot === 'hiba') && (
        <div className="row-baseline meta kutatas__rangsor">
          {r.allapot === 'kesz' && (
            <span>
              <Icon name="spark" size={12} /> AI-rangsor: erősen, részben, kevéssé releváns, végül a besorolatlanok. A
              részletes értékelés relevanciája felülírja az előszűrését, a cikk ekkor a helyére kerül. Csoporton belül
              elöl a kiemelt cikk(ek), utána a forrás sorrendje.
              {r.modell && (
                <>
                  {' '}
                  <span className="mono">{r.modell}</span>
                </>
              )}
            </span>
          )}
          <button
            type="button"
            className="btn btn--sm push-right"
            disabled={ujraRangsor.busy}
            title="Az aktív (nem kivett) találatok újrarangsorolása"
            onClick={async () => {
              if (await ujraRangsor.run(() => api.ujraRangsorol(k.id))) onValtozas();
            }}
          >
            Újrarangsorolás
          </button>
        </div>
      )}
      {ujraRangsor.error && (
        <p className="form-error" role="alert">
          {ujraRangsor.error}
        </p>
      )}
      {r.allapot === 'kesz' && r.figyelmeztetesek.length > 0 && (
        <details className="meta">
          <summary>Az AI-rangsor megjegyzései ({r.figyelmeztetesek.length})</summary>
          <ul className="klinstatus__warn">
            {r.figyelmeztetesek.map((w, i) => (
              <li key={i}>
                <Icon name="alert" size={12} /> {w}
              </li>
            ))}
          </ul>
        </details>
      )}
      {ertekeles.error && (
        <p className="form-error" role="alert">
          Az értékelés nem indult el: {ertekeles.error}
        </p>
      )}
      {lista.state.status === 'loading' && <Loading what="Cikkek" />}
      {lista.state.status === 'error' && <ErrorState error={lista.state.error} onRetry={lista.reload} />}
      {adat && adat.cikkek.length === 0 && <p className="meta">Nincs cikk ebben a nézetben.</p>}
      {adat?.cikkek.map((c, i) => (
        <CikkKartya
          key={c.id}
          c={c}
          // a helyezés a listában (a relevancia változásával együtt mozog)
          sorszam={adat.oldal * adat.meret + i + 1}
          onModosit={(m) => void modosit(c, m)}
        />
      ))}
      {adat && adat.oldalak > 1 && <Lapozo oldal={adat.oldal} oldalak={adat.oldalak} onLapoz={setOldal} />}
    </section>
  );
}

function Lapozo({ oldal, oldalak, onLapoz }: { oldal: number; oldalak: number; onLapoz: (o: number) => void }) {
  return (
    <nav className="kutatas__lapozo" aria-label="Lapozás">
      <button type="button" className="btn btn--sm" disabled={oldal === 0} onClick={() => onLapoz(oldal - 1)}>
        ‹ Előző
      </button>
      <span className="meta" aria-current="page">
        {oldal + 1}. oldal / {oldalak}
      </span>
      <button type="button" className="btn btn--sm" disabled={oldal >= oldalak - 1} onClick={() => onLapoz(oldal + 1)}>
        Következő ›
      </button>
    </nav>
  );
}

const FORRAS_FELIRAT: Record<KutatasForras, string> = {
  PUBMED: 'PubMed',
  COCHRANE: 'Cochrane',
  EUROPE_PMC: 'Europe PMC',
};

const RELEVANCIA_FELIRAT = { magas: 'Erősen releváns', kozepes: 'Részben releváns', alacsony: 'Kevéssé releváns' };

function CikkKartya({
  c,
  sorszam = c.sorszam,
  onModosit,
}: {
  c: Cikk;
  sorszam?: number;
  onModosit?: (m: { kivett?: boolean; konyvjelzo?: boolean }) => void;
}) {
  const e = c.ertekeles;
  const szerzok = c.szerzok.length > 3 ? `${c.szerzok.slice(0, 3).join(', ')} et al.` : c.szerzok.join(', ');
  // a kiemelés az előszűrésen alapul: ha a részletes értékelés nem tartja erősen relevánsnak, nem emeljük ki
  // (a kártya relevanciájával ellentmondana)
  const kiemelt = c.rangsor.kiemeles !== null && !(e.allapot === 'kesz' && e.relevancia !== 'magas');
  return (
    <article
      className={`cikk${c.kivett ? ' cikk--kivett' : ''}${kiemelt ? ' cikk--kiemelt' : ''}`}
      aria-label={c.cim}
    >
      {kiemelt && (
        <div className="cikk__kiemeles">
          <span className="cikk__kiemeles-cimke">
            <Icon name="spark" size={12} />{' '}
            {c.rangsor.kiemeles === 1 ? 'AI: a legrelevánsabb' : `AI-kiemelés · ${c.rangsor.kiemeles}.`}
          </span>
          <p className="cikk__szoveg">{c.rangsor.kiemelesIndok}</p>
        </div>
      )}
      <div className="cikk__fej">
        <span className="cikk__sorszam">{sorszam}.</span>
        <h4 className="cikk__cim" lang="en">
          <a href={c.pubmedUrl} target="_blank" rel="noreferrer noopener">
            {c.cim}
          </a>
        </h4>
        {onModosit && (
          <div className="cikk__muveletek">
            <button
              type="button"
              className={`iconbtn iconbtn--ghost${c.konyvjelzo ? ' cikk__csillag--on' : ''}`}
              aria-pressed={c.konyvjelzo}
              aria-label={c.konyvjelzo ? 'Könyvjelző törlése' : 'Könyvjelző'}
              title={c.konyvjelzo ? 'Könyvjelző törlése' : 'Könyvjelző (kutatási eredmény)'}
              onClick={() => onModosit({ konyvjelzo: !c.konyvjelzo })}
            >
              <Icon name="star" size={18} />
            </button>
            <button
              type="button"
              className="btn btn--sm"
              onClick={() => onModosit({ kivett: !c.kivett })}
              title={c.kivett ? 'Visszavétel a találatok közé' : 'Kivétel a találatok közül'}
            >
              {c.kivett ? 'Visszavétel' : 'Kivétel'}
            </button>
          </div>
        )}
      </div>
      <p className="meta cikk__bib">
        {szerzok || 'Szerző nélkül'}
        {c.ev && <> · {c.ev}</>}
        {c.folyoirat && <> · {c.folyoirat}</>} · Hivatkozás:{' '}
        {c.hivatkozas === null ? 'nincs adat' : `${c.hivatkozas.toLocaleString('hu-HU')} (iCite)`}
      </p>
      <div className="cikk__cimkek">
        {c.forrasok.includes('COCHRANE') && <span className="tag tag--forras">Cochrane</span>}
        {c.forrasok.includes('EUROPE_PMC') && !c.forrasok.includes('PUBMED') && (
          <span className="tag cikk__tipus">Europe PMC</span>
        )}
        {c.tipusok
          .filter((t) => t !== 'Journal Article')
          .slice(0, 3)
          .map((t) => (
            <span key={t} className="tag cikk__tipus" lang="en">
              {t}
            </span>
          ))}
        <a className="meta" href={c.pubmedUrl} target="_blank" rel="noreferrer noopener">
          PMID {c.pmid} <Icon name="external" size={12} />
        </a>
        {c.doiUrl && (
          <a className="meta" href={c.doiUrl} target="_blank" rel="noreferrer noopener">
            DOI <Icon name="external" size={12} />
          </a>
        )}
      </div>

      <section className="cikk__ai" aria-label="AI-értékelés">
        <span className="tag tag--modell">
          <Icon name="spark" size={12} /> AI · nem ellenőrzött
        </span>
        {e.allapot !== 'kesz' && c.rangsor.elozetes && (
          <span className={`cikk__relevancia cikk__relevancia--${c.rangsor.elozetes}`}>
            Előszűrés: {RELEVANCIA_FELIRAT[c.rangsor.elozetes].toLowerCase()}
          </span>
        )}
        {e.allapot === 'nincs' && <p className="meta">Értékelés lapozáskor készül.</p>}
        {e.allapot === 'keszul' && (
          <p className="meta" role="status">
            <span className="kutatas__spinner" aria-hidden="true" /> A helyi modell olvassa az absztraktot…
          </p>
        )}
        {e.allapot === 'hiba' && <p className="meta">AI-összefoglaló most nem készült: {e.hiba}</p>}
        {e.allapot === 'kesz' && (
          <>
            {e.relevancia && (
              <span className={`cikk__relevancia cikk__relevancia--${e.relevancia}`}>
                {RELEVANCIA_FELIRAT[e.relevancia]}
              </span>
            )}
            <p className="cikk__szoveg">{e.osszefoglalo}</p>
            <p className="cikk__szoveg">
              <strong>Miért releváns ennél a betegnél: </strong>
              {e.indoklas}
            </p>
            {e.figyelmeztetesek.length > 0 && (
              <ul className="klinstatus__warn" aria-label="Ellenőrizendő">
                {e.figyelmeztetesek.map((w, i) => (
                  <li key={i}>
                    <Icon name="alert" size={12} /> {w}
                  </li>
                ))}
              </ul>
            )}
            {e.modell && <p className="meta mono">{e.modell}</p>}
          </>
        )}
      </section>

      {c.absztrakt && (
        <details className="cikk__absztrakt">
          <summary>Eredeti absztrakt</summary>
          <p lang="en">{c.absztrakt}</p>
        </details>
      )}
    </article>
  );
}

// ---------------------------------------------------------------- előzmények, könyvjelzők

function Korabbiak({ onNyit }: { onNyit: (id: number) => void }) {
  const { patient } = usePatient();
  const { state, reload } = useAsync(() => api.listKutatasok(patient.id), [patient.id]);
  if (state.status === 'loading') return <Loading what="Kutatások" />;
  if (state.status === 'error') return <ErrorState error={state.error} onRetry={reload} />;
  if (state.data.length === 0) return <p className="meta">Még nincs kutatás ennél a betegnél.</p>;
  return (
    <ul className="kutatas__lista">
      {state.data.map((k) => (
        <li key={k.id}>
          <button type="button" className="kutatas__sor" onClick={() => onNyit(k.id)}>
            <span className="mono kutatas__kifejezes">{k.kifejezes}</span>
            <span className="meta">
              {formatDatumIdo(k.inditva)} ·{' '}
              {k.allapot === 'keres'
                ? 'keresés folyamatban'
                : k.allapot === 'hiba'
                  ? 'sikertelen'
                  : `${k.cikkek.osszes} cikk, ★ ${k.cikkek.konyvjelzo}`}
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}

function Konyvjelzok() {
  const { patient } = usePatient();
  const { state, reload } = useAsync(() => api.listKonyvjelzok(patient.id), [patient.id]);
  if (state.status === 'loading') return <Loading what="Könyvjelzők" />;
  if (state.status === 'error') return <ErrorState error={state.error} onRetry={reload} />;
  if (state.data.length === 0)
    return <p className="meta">Még nincs könyvjelzett cikk. A találatoknál a ★ gombbal jelölheted meg.</p>;
  return (
    <div className="kutatas__talalatok">
      {state.data.map((b) => (
        <div key={b.cikk.id}>
          <p className="meta">
            Kutatás {formatDatumIdo(b.inditva)}: <span className="mono">{b.kifejezes}</span>
          </p>
          <CikkKartya c={b.cikk} />
        </div>
      ))}
    </div>
  );
}
