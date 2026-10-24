import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useAkcio } from '../lib/useAkcio';
import { Icon } from './Icon';

/** A szerkesztő mentetlen tartalma — az export előbb elmenti */
export type Mentetlen = { id: number; html: () => string };

const ESZKOZOK: { parancs: string; cimke: string; jel: string; className?: string }[] = [
  { parancs: 'bold', cimke: 'Félkövér', jel: 'B', className: 'rte__b' },
  { parancs: 'italic', cimke: 'Dőlt', jel: 'I', className: 'rte__i' },
  { parancs: 'underline', cimke: 'Aláhúzott', jel: 'U', className: 'rte__u' },
  { parancs: 'insertUnorderedList', cimke: 'Felsorolás', jel: '•≡' },
  { parancs: 'insertOrderedList', cimke: 'Számozott lista', jel: '1≡' },
];

type Props = {
  /** az AI-dokumentum azonosítója (a mentetlen tartalom ehhez tartozik) */
  id: number;
  /** a megjelenített szöveg: az orvos szerkesztése, ha van, különben aiHtml (a Spring escape-elte / szűrte) */
  html: string;
  aiHtml: string;
  szerkesztve: string | null;
  ment: (html: string) => Promise<unknown>;
  regisztral: (m: Mentetlen | null) => void;
  cimke: string;
  /** a szerkeszthető rész fölött, nem szerkeszthető tartalom (pl. az epikrízis fejléce) */
  fejlec?: ReactNode;
  /** ha meg van adva, a Szerkesztés gomb le van tiltva, ez a tooltipje (pl. Informálódás mód) */
  tiltasOka?: string;
  /** összecsukható fejléc a címkével (mint a Kórtörténeti idővonal); a szerkesztő csukva is él, a mentetlen szöveg megmarad */
  osszecsukhato?: boolean;
};

/**
 * Közös rich text szerkesztő (Medical history, Epikrízis). A dokumentum alapból olvasható nézetben jelenik
 * meg; a "Szerkesztés" gomb nyitja meg (contentEditable + execCommand: félkövér, dőlt, aláhúzott,
 * felsorolás, számozott lista). A mentett HTML-t a Spring szűri; az AI eredetije megmarad és megnézhető.
 */
export function DokSzerkeszto({
  id,
  html,
  aiHtml,
  szerkesztve,
  ment,
  regisztral,
  cimke,
  fejlec,
  tiltasOka,
  osszecsukhato,
}: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const [nyitva, setNyitva] = useState(true);
  const [modositott, setModositott] = useState(false);
  const [eredeti, setEredeti] = useState(false);
  // olvasási nézet az alapértelmezés; mentés után a hívó key-je újraindítja → ismét olvasási nézet
  const [szerkeszt, setSzerkeszt] = useState(false);
  const mentes = useAkcio();

  // A tartalmat csak a szerkesztő megnyitásakor írjuk be: gépelés közben a React nem nyúl hozzá
  useEffect(() => {
    if (szerkeszt && ref.current) ref.current.innerHTML = html;
  }, [html, szerkeszt]);

  useEffect(() => {
    regisztral(modositott ? { id, html: () => ref.current?.innerHTML ?? html } : null);
    return () => regisztral(null);
  }, [modositott, id, html, regisztral]);

  function parancs(p: string) {
    ref.current?.focus();
    // Enter után <p> legyen, ne <div> (a Word-export és a szűrő is a bekezdést várja)
    document.execCommand('defaultParagraphSeparator', false, 'p');
    document.execCommand(p);
    setModositott(true);
  }

  async function mentes_() {
    await mentes.run(() => ment(ref.current?.innerHTML ?? ''));
  }

  function megse() {
    if (modositott && !window.confirm('Elveted a nem mentett módosításokat?')) return;
    setModositott(false);
    setSzerkeszt(false);
  }

  function visszaallit() {
    if (ref.current) ref.current.innerHTML = aiHtml;
    setModositott(true);
  }

  const eredetiGomb = szerkesztve && (
    <button type="button" className="btn btn--sm" onClick={() => setEredeti((v) => !v)}>
      {eredeti ? 'AI eredeti elrejtése' : 'AI eredeti megtekintése'}
    </button>
  );

  return (
    <section className="kortortenet__card" aria-label={cimke}>
      {osszecsukhato && (
        <button
          type="button"
          className="kortortenet__tlhead"
          aria-expanded={nyitva}
          onClick={() => setNyitva((v) => !v)}
        >
          <Icon name="doc" size={18} />
          <span className="kortortenet__tltitle">{cimke}</span>
          {modositott && <span className="kortortenet__count">mentetlen</span>}
          <span className="push-right" aria-hidden="true">
            <Icon name={nyitva ? 'chevronUp' : 'chevronDown'} size={16} />
          </span>
        </button>
      )}
      {/* hidden, nem levétel: a contentEditable tartalma és a regisztrált mentetlen szöveg csukva is megmarad */}
      <div className="kortortenet__body" hidden={!nyitva}>
        {!szerkeszt && (
          <div className="rte">
            <div className="rte__bar">
              <span className="push-right" />
              {eredetiGomb}
              <button
                type="button"
                className="btn btn--sm btn--primary"
                onClick={() => setSzerkeszt(true)}
                disabled={!!tiltasOka}
                title={tiltasOka}
              >
                <Icon name="pencil" size={14} />
                Szerkesztés
              </button>
            </div>
            {fejlec}
            {/* a Spring által előállított (escape-elt) vagy szűrt HTML */}
            <div className="rte__body kortortenet__doc" dangerouslySetInnerHTML={{ __html: html }} />
          </div>
        )}
        {szerkeszt && (
          <div className="rte">
            <div className="rte__bar" role="toolbar" aria-label="Formázás">
              {ESZKOZOK.map((e, i) => (
                <button
                  key={e.parancs}
                  type="button"
                  className={`rte__btn ${e.className ?? ''}${i === 3 ? ' rte__btn--sep' : ''}`}
                  title={e.cimke}
                  aria-label={e.cimke}
                  // mousedown: a kijelölés ne vesszen el a gombra kattintáskor
                  onMouseDown={(ev) => {
                    ev.preventDefault();
                    parancs(e.parancs);
                  }}
                >
                  {e.jel}
                </button>
              ))}
              <span className="push-right" />
              {eredetiGomb}
              <button type="button" className="btn btn--sm" onClick={visszaallit} title="Az AI szövegére">
                Visszaállítás
              </button>
              <button type="button" className="btn btn--sm" onClick={megse}>
                Mégse
              </button>
              <button
                type="button"
                className="btn btn--sm btn--primary"
                onClick={() => void mentes_()}
                disabled={!modositott || mentes.busy}
              >
                <Icon name="check" size={14} />
                {mentes.busy ? 'Mentés…' : modositott ? 'Mentés' : 'Mentve'}
              </button>
            </div>
            {fejlec}
            <div
              ref={ref}
              className="rte__body kortortenet__doc"
              contentEditable
              suppressContentEditableWarning
              role="textbox"
              aria-multiline="true"
              aria-label={`${cimke} (szerkeszthető)`}
              spellCheck
              onInput={() => setModositott(true)}
            />
          </div>
        )}
        {mentes.error && (
          <p className="form-error" role="alert">
            {mentes.error}
          </p>
        )}
        {eredeti && (
          <div className="kortortenet__orig">
            <span className="klinstatus__label">
              <Icon name="spark" size={12} /> Az AI eredeti szövege (nem szerkeszthető)
            </span>
            <div className="kortortenet__doc" dangerouslySetInnerHTML={{ __html: aiHtml }} />
          </div>
        )}
      </div>
    </section>
  );
}
