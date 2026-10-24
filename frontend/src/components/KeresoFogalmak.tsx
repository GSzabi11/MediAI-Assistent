import { useId, useState, type FormEvent } from 'react';
import { bont, epit, KIFEJEZES_HOSSZ, KIFEJEZES_MAX, tisztit, type Fogalom, type Operator } from '../lib/keresoKifejezes';
import { Icon } from './Icon';

/**
 * A keresőkifejezés kulcsszavai fogalmanként, chipként (docs/literature-research.md, „Kulcsszavak”).
 * Minden változás azonnal újraépíti a kifejezést (`lib/keresoKifejezes`); a kifejezés marad az egyetlen
 * igazság, a chipek belőle bomlanak vissza.
 */
type Szerk =
  | { fogalom: number; mezo: 'mesh' | 'uj' | number }
  | { fogalom: 'uj' }
  | null;

export function KeresoFogalmak({ kifejezes, onChange }: { kifejezes: string; onChange: (k: string) => void }) {
  const fogalmak = bont(kifejezes);
  const [szerk, setSzerk] = useState<Szerk>(null);

  if (fogalmak === null) {
    return (
      <p className="meta kutatas__fogalmak-nem">
        <Icon name="info" size={12} /> A kifejezés kézzel átírt szerkezetű, nem bontható kulcsszavakra — a fenti mezőben
        szerkeszthető.
      </p>
    );
  }

  const ment = (uj: Fogalom[]) => {
    onChange(epit(uj));
    setSzerk(null);
  };
  const modosit = (i: number, f: (x: Fogalom) => Fogalom) => ment(fogalmak.map((x, j) => (j === i ? f(x) : x)));

  return (
    <div className="kutatas__fogalmak" aria-label="A keresőkifejezés kulcsszavai">
      <p className="meta">
        Egy fogalmon belül bármelyik alak elég (VAGY); a fogalmak között választhatsz: ÉS (mind kell) vagy VAGY
        (bármelyik elég). A változás azonnal a kifejezésbe kerül.
      </p>
      {fogalmak.map((f, i) => (
        <div key={i} className="kutatas__fogalom">
          {i > 0 && (
            <OperatorValaszto
              ertek={f.elotte}
              sorszam={i + 1}
              onChange={(op) => modosit(i, (x) => ({ ...x, elotte: op }))}
            />
          )}
          <ul className="chips">
            {f.mesh !== null && (
              <li className="chip chip--form">
                {szerk && szerk.fogalom === i && 'mezo' in szerk && szerk.mezo === 'mesh' ? (
                  <SzoUrlap
                    kezdo={f.mesh}
                    cimke="MeSH-fejléc"
                    onMent={(szo) => modosit(i, (x) => ({ ...x, mesh: szo }))}
                    onMegse={() => setSzerk(null)}
                  />
                ) : (
                  <Chip
                    szo={f.mesh}
                    mesh
                    onSzerk={() => setSzerk({ fogalom: i, mezo: 'mesh' })}
                    onTorol={() => modosit(i, (x) => ({ ...x, mesh: null }))}
                  />
                )}
              </li>
            )}
            {f.szinonimak.map((s, k) => (
              <li key={k} className="chip chip--form">
                {szerk && szerk.fogalom === i && 'mezo' in szerk && szerk.mezo === k ? (
                  <SzoUrlap
                    kezdo={s}
                    cimke="Kulcsszó"
                    onMent={(szo) =>
                      modosit(i, (x) => ({ ...x, szinonimak: x.szinonimak.map((y, n) => (n === k ? szo : y)) }))
                    }
                    onMegse={() => setSzerk(null)}
                  />
                ) : (
                  <Chip
                    szo={s}
                    onSzerk={() => setSzerk({ fogalom: i, mezo: k })}
                    onTorol={() => modosit(i, (x) => ({ ...x, szinonimak: x.szinonimak.filter((_, n) => n !== k) }))}
                  />
                )}
              </li>
            ))}
            <li>
              {szerk && szerk.fogalom === i && 'mezo' in szerk && szerk.mezo === 'uj' ? (
                <span className="chip chip--form">
                  <SzoUrlap
                    kezdo=""
                    cimke="Új alak ehhez a fogalomhoz"
                    meshValaszthato={f.mesh === null}
                    onMent={(szo, mesh) =>
                      modosit(i, (x) => (mesh ? { ...x, mesh: szo } : { ...x, szinonimak: [...x.szinonimak, szo] }))
                    }
                    onMegse={() => setSzerk(null)}
                  />
                </span>
              ) : (
                <button
                  type="button"
                  className="chip chip--add"
                  aria-label={`Alak hozzáadása a(z) ${i + 1}. fogalomhoz`}
                  title="Szinonima hozzáadása (VAGY)"
                  onClick={() => setSzerk({ fogalom: i, mezo: 'uj' })}
                >
                  <Icon name="plus" size={14} strokeWidth={2.6} />
                </button>
              )}
            </li>
            <li className="push-right">
              <button
                type="button"
                className="btn btn--sm"
                onClick={() => ment(fogalmak.filter((_, j) => j !== i))}
                aria-label={`A(z) ${i + 1}. fogalom törlése`}
              >
                <Icon name="x" size={12} /> Fogalom
              </button>
            </li>
          </ul>
        </div>
      ))}
      <div className="kutatas__fogalom">
        {szerk && szerk.fogalom === 'uj' ? (
          <span className="chip chip--form">
            <SzoUrlap
              kezdo=""
              cimke="Új fogalom (ÉS)"
              meshValaszthato
              onMent={(szo, mesh) =>
                ment([
                  ...fogalmak,
                  mesh ? { mesh: szo, szinonimak: [], elotte: 'AND' } : { mesh: null, szinonimak: [szo], elotte: 'AND' },
                ])
              }
              onMegse={() => setSzerk(null)}
            />
          </span>
        ) : (
          <button type="button" className="btn btn--sm" onClick={() => setSzerk({ fogalom: 'uj' })}>
            <Icon name="plus" size={12} /> Új fogalom (ÉS)
          </button>
        )}
      </div>
      {new Set(fogalmak.slice(1).map((f) => f.elotte)).size > 1 && (
        <p className="meta">
          <Icon name="info" size={12} /> Vegyes ÉS / VAGY: a PubMed balról jobbra értékel — „A ÉS B VAGY C” jelentése
          „(A ÉS B) VAGY C”.
        </p>
      )}
      {kifejezes.length > KIFEJEZES_MAX && (
        <p className="form-error" role="alert">
          A kifejezés hosszabb {KIFEJEZES_MAX} karakternél — törölj néhány alakot.
        </p>
      )}
    </div>
  );
}

function OperatorValaszto({
  ertek,
  sorszam,
  onChange,
}: {
  ertek: Operator;
  sorszam: number;
  onChange: (op: Operator) => void;
}) {
  return (
    <div className="kutatas__op" role="group" aria-label={`Kapcsolat a(z) ${sorszam}. fogalom előtt`}>
      {(['AND', 'OR'] as const).map((op) => (
        <button
          key={op}
          type="button"
          className={`kutatas__opgomb${ertek === op ? ' kutatas__opgomb--on' : ''}`}
          aria-pressed={ertek === op}
          onClick={() => ertek !== op && onChange(op)}
        >
          {op === 'AND' ? 'ÉS' : 'VAGY'}
        </button>
      ))}
    </div>
  );
}

function Chip({ szo, mesh, onSzerk, onTorol }: { szo: string; mesh?: boolean; onSzerk: () => void; onTorol: () => void }) {
  return (
    <>
      <button
        type="button"
        className="kutatas__chipszo"
        onClick={onSzerk}
        title={mesh ? 'MeSH-fejléc ([MeSH Terms] és [tiab]) — kattints a szerkesztéshez' : 'Szabad szó a címben / absztraktban ([tiab]) — kattints a szerkesztéshez'}
      >
        {mesh && <span className="chip__tag">MeSH</span>}
        {szo}
      </button>
      <button type="button" className="chip__btn" aria-label={`„${szo}” törlése`} onClick={onTorol}>
        <Icon name="x" size={12} strokeWidth={2.8} />
      </button>
    </>
  );
}

function SzoUrlap({
  kezdo,
  cimke,
  meshValaszthato,
  onMent,
  onMegse,
}: {
  kezdo: string;
  cimke: string;
  meshValaszthato?: boolean;
  onMent: (szo: string, mesh: boolean) => void;
  onMegse: () => void;
}) {
  const id = useId();
  const [szo, setSzo] = useState(kezdo);
  const [mesh, setMesh] = useState(false);
  const tiszta = tisztit(szo);
  const kuld = (e: FormEvent) => {
    e.preventDefault();
    if (tiszta) onMent(tiszta, mesh);
  };
  return (
    <form className="kutatas__szourlap" onSubmit={kuld}>
      <label htmlFor={id} className="sr-only">
        {cimke}
      </label>
      <input
        id={id}
        className="input input--chip mono"
        autoFocus
        maxLength={KIFEJEZES_HOSSZ}
        value={szo}
        placeholder={cimke}
        onChange={(e) => setSzo(e.target.value)}
        onKeyDown={(e) => e.key === 'Escape' && onMegse()}
      />
      {meshValaszthato && (
        <label className="kutatas__meshvalaszto" title="MeSH-fejlécként: [MeSH Terms] és [tiab] is">
          <input type="checkbox" checked={mesh} onChange={(e) => setMesh(e.target.checked)} /> MeSH
        </label>
      )}
      <button type="submit" className="chip__btn" aria-label="Mentés" disabled={!tiszta}>
        <Icon name="check" size={12} strokeWidth={2.8} />
      </button>
      <button type="button" className="chip__btn" aria-label="Mégse" onClick={onMegse}>
        <Icon name="x" size={12} strokeWidth={2.8} />
      </button>
    </form>
  );
}
