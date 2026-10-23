import { useEffect, useId, useState, type FormEvent } from 'react';
import { csoportApi, csoportok, type CsoportElem, type FindingCategoryDto } from '../api/felvetelCsoportok';
import { formatDatumIdo } from '../lib/format';
import { useAkcio } from '../lib/useAkcio';
import { useAsync } from '../lib/useAsync';
import { Icon } from './Icon';
import { ErrorState, Loading } from './shell';

const turnLink = (turnIds: number[]) => `[${turnIds.map((t) => `#${String(t).padStart(2, '0')}`).join(', ')}]`;

/**
 * Az átirat csoportjai: panaszok (BNO), diagnózisok (BNO), beutalók, vizsgálati megállapítások,
 * javasolt gyógyszerek, javaslatok / kontroll. A felvétel elkészültekor az AI (MedGemma) kigyűjti őket,
 * mindegyik a forrás-megszólalására hivatkozik; az orvos hozzáad, szerkeszt, töröl. Az AI eredetije
 * megmarad (szerkesztésnél lenyitható). BNO-kódot a modell nem ad — csak ha elhangzott, vagy az orvos írja be.
 */
export function FelvetelCsoportokPanel({
  felvetelId,
  frissites,
  onUgras,
}: {
  felvetelId: number;
  /** a szülő növeli, ha az átirat kijelöléséből elem került valamelyik csoportba */
  frissites: number;
  onUgras: (turnIds: number[]) => void;
}) {
  const lista = useAsync(() => csoportApi.list(felvetelId), [felvetelId]);
  const { busy, error, run } = useAkcio();
  const adat = lista.state.status === 'ok' ? lista.state.data : undefined;
  const keszul = adat?.futas?.allapot === 'feldolgozas';

  useEffect(() => {
    if (frissites > 0) void lista.refresh().catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [frissites]);

  useEffect(() => {
    if (!keszul) return;
    const t = window.setInterval(() => void lista.refresh().catch(() => undefined), 3000);
    return () => window.clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keszul]);

  const ujra = () => {
    const erintett = (adat?.elemek ?? []).some((e) => e.forras === 'ai' && e.szerkesztett);
    if (
      erintett &&
      !window.confirm(
        'Újrakinyeréskor az AI-elemek cserélődnek (a szerkesztéseik az adatbázisban megmaradnak, de a listából eltűnnek). A kézzel hozzáadott elemek maradnak. Folytatod?',
      )
    ) {
      return;
    }
    void run(async () => {
      await csoportApi.kinyer(felvetelId);
      await lista.refresh();
    });
  };

  return (
    <section className="kwpanel" aria-label="Csoportok">
      <div className="row-baseline">
        <h3 className="h2 h2--sm">Csoportok</h3>
        <span className="tag tag--modell">AI · ellenőrizendő</span>
        <button type="button" className="btn btn--sm push-right" onClick={ujra} disabled={busy || keszul}>
          <Icon name="spark" size={14} />
          {adat?.futas ? 'Újrakinyerés' : 'Kinyerés'}
        </button>
      </div>
      {lista.state.status === 'loading' && <Loading what="Csoportok" />}
      {lista.state.status === 'error' && <ErrorState error={lista.state.error} onRetry={lista.reload} />}
      {keszul && (
        <p className="meta" role="status">
          <span className="stat stat--feldolgozas">Készül</span> az AI kigyűjti a csoportokat az átiratból…
        </p>
      )}
      {adat?.futas?.allapot === 'hiba' && (
        <p className="form-error" role="alert">
          {adat.futas.hiba}
        </p>
      )}
      {adat && (
        <div className="findgroups">
          {csoportok.map((cs) => (
            <CsoportLista
              key={cs.kategoria}
              felvetelId={felvetelId}
              kategoria={cs.kategoria}
              cim={cs.cim}
              bno={cs.bno}
              pelda={cs.pelda}
              elemek={adat.elemek.filter((e) => e.kategoria === cs.kategoria)}
              onValtozott={lista.refresh}
              onUgras={onUgras}
            />
          ))}
        </div>
      )}
      {adat?.futas && adat.futas.figyelmeztetesek.length > 0 && (
        <details className="subsection__more">
          <summary>Kinyerési megjegyzések ({adat.futas.figyelmeztetesek.length})</summary>
          <ul className="itemlist">
            {adat.futas.figyelmeztetesek.map((w, i) => (
              <li key={i} className="meta">
                {w}
              </li>
            ))}
          </ul>
        </details>
      )}
      {adat?.futas && adat.futas.allapot !== 'feldolgozas' && (
        <p className="meta mono">
          {adat.futas.modell ?? '—'} · {formatDatumIdo(adat.futas.kerve)}
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

function CsoportLista({
  felvetelId,
  kategoria,
  cim,
  bno,
  pelda,
  elemek,
  onValtozott,
  onUgras,
}: {
  felvetelId: number;
  kategoria: FindingCategoryDto;
  cim: string;
  bno: boolean;
  pelda: string;
  elemek: CsoportElem[];
  onValtozott: () => Promise<void>;
  onUgras: (turnIds: number[]) => void;
}) {
  // null: nincs nyitott űrlap; 'uj': új elem; szám: annak az elemnek a szerkesztése
  const [nyitott, setNyitott] = useState<'uj' | number | null>(null);
  const { busy, error, run } = useAkcio();

  const ment = (szoveg: string, kod: string) =>
    run(async () => {
      if (nyitott === 'uj') await csoportApi.hozzaad(felvetelId, kategoria, szoveg, bno ? kod : null);
      else if (nyitott !== null) await csoportApi.szerkeszt(felvetelId, nyitott, szoveg, bno ? kod : null);
      await onValtozott();
      setNyitott(null);
    });

  const torol = (e: CsoportElem) => {
    if (!window.confirm(`Törlöd: „${e.szoveg}”?`)) return;
    void run(async () => {
      await csoportApi.torol(felvetelId, e.id);
      await onValtozott();
    });
  };

  return (
    <div className="findgroup">
      <div className="row-baseline">
        <h4 className="findgroup__title">{cim}</h4>
        <button
          type="button"
          className="chip__btn push-right"
          aria-label={`Új elem: ${cim}`}
          onClick={() => setNyitott('uj')}
          disabled={busy || nyitott !== null}
        >
          <Icon name="plus" size={14} strokeWidth={2.6} />
        </button>
      </div>
      {elemek.length === 0 && nyitott !== 'uj' && <p className="meta">—</p>}
      <ul className="itemlist findgroup__list">
        {elemek.map((e) =>
          nyitott === e.id ? (
            <li key={e.id}>
              <ElemUrlap
                kezdo={e.szoveg}
                kezdoBno={e.bno ?? ''}
                bno={bno}
                pelda={pelda}
                busy={busy}
                onMent={ment}
                onMegse={() => setNyitott(null)}
              />
            </li>
          ) : (
            <li key={e.id} className="finding">
              <span className="finding__text">
                {e.forras === 'ai' && <span className="chip__tag">AI</span>}
                {e.szoveg}
                {e.bno && <span className="badge mono finding__bno">BNO {e.bno}</span>}{' '}
                {e.turnIds.length > 0 && (
                  <button type="button" className="linkbtn mono" onClick={() => onUgras(e.turnIds)}>
                    {turnLink(e.turnIds)}
                  </button>
                )}
              </span>
              <span className="finding__actions">
                <button
                  type="button"
                  className="chip__btn"
                  aria-label={`„${e.szoveg}” szerkesztése`}
                  onClick={() => setNyitott(e.id)}
                  disabled={busy || nyitott !== null}
                >
                  <Icon name="pencil" size={12} />
                </button>
                <button
                  type="button"
                  className="chip__btn"
                  aria-label={`„${e.szoveg}” törlése`}
                  onClick={() => torol(e)}
                  disabled={busy || nyitott !== null}
                >
                  <Icon name="x" size={12} strokeWidth={2.8} />
                </button>
              </span>
              {e.jelzes && (
                <p className="turn__note" role="note">
                  <Icon name="alert" size={13} /> {e.jelzes}
                </p>
              )}
              {e.szerkesztett && (
                <details className="turn__orig">
                  <summary>{e.forras === 'ai' ? 'Orvos szerkesztette — eredeti (AI)' : 'Szerkesztve — eredeti'}</summary>
                  <p className="meta">
                    {e.eredetiSzoveg}
                    {e.eredetiBno && ` (BNO ${e.eredetiBno})`}
                  </p>
                </details>
              )}
            </li>
          ),
        )}
        {nyitott === 'uj' && (
          <li>
            <ElemUrlap
              kezdo=""
              kezdoBno=""
              bno={bno}
              pelda={pelda}
              busy={busy}
              onMent={ment}
              onMegse={() => setNyitott(null)}
            />
          </li>
        )}
      </ul>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

function ElemUrlap({
  kezdo,
  kezdoBno,
  bno,
  pelda,
  busy,
  onMent,
  onMegse,
}: {
  kezdo: string;
  kezdoBno: string;
  bno: boolean;
  pelda: string;
  busy: boolean;
  onMent: (szoveg: string, kod: string) => void;
  onMegse: () => void;
}) {
  const [szoveg, setSzoveg] = useState(kezdo);
  const [kod, setKod] = useState(kezdoBno);
  const szovegId = useId();
  const kodId = useId();

  const kuld = (e: FormEvent) => {
    e.preventDefault();
    if (szoveg.trim()) onMent(szoveg, kod);
  };

  return (
    <form className="finding__form" onSubmit={kuld}>
      <label htmlFor={szovegId} className="sr-only">
        Szöveg
      </label>
      <input
        id={szovegId}
        className="input"
        autoFocus
        maxLength={500}
        value={szoveg}
        placeholder={pelda}
        onChange={(e) => setSzoveg(e.target.value)}
        onKeyDown={(e) => e.key === 'Escape' && onMegse()}
      />
      {bno && (
        <>
          <label htmlFor={kodId} className="sr-only">
            BNO-kód
          </label>
          <input
            id={kodId}
            className="input input--bno mono"
            maxLength={10}
            value={kod}
            placeholder="BNO (pl. R07.4)"
            onChange={(e) => setKod(e.target.value.toUpperCase())}
            onKeyDown={(e) => e.key === 'Escape' && onMegse()}
          />
        </>
      )}
      <button type="submit" className="chip__btn" aria-label="Mentés" disabled={busy || !szoveg.trim()}>
        <Icon name="check" size={12} strokeWidth={2.8} />
      </button>
      <button type="button" className="chip__btn" aria-label="Mégse" onClick={onMegse}>
        <Icon name="x" size={12} strokeWidth={2.8} />
      </button>
    </form>
  );
}
