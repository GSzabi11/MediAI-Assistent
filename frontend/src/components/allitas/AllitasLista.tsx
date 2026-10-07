import { useMemo, useState } from 'react';
import type { ClaimResponse, ClaimType, Forras, Sulyossag } from '../../api/types';
import { Icon } from '../Icon';
import { AllitasKartya } from './AllitasKartya';

type Szuro = 'mind' | ClaimType | 'labor';
const rang: Record<Sulyossag, number> = { critical: 0, warning: 1, info: 2 };

/**
 * Állítás-lista szűrővel. A kritikus állítások a szűrőtől függetlenül
 * mindig látszanak, és mindig elöl vannak.
 */
export function AllitasLista({
  allitasok,
  activeForras,
  onDecided,
  laborDokIds,
  defaultSzuro = 'mind',
}: {
  allitasok: ClaimResponse[];
  activeForras?: (f: Forras) => boolean;
  onDecided: (id: number) => Promise<void>;
  laborDokIds?: Set<number>;
  defaultSzuro?: Szuro;
}) {
  const [szuro, setSzuro] = useState<Szuro>(defaultSzuro);

  const isLabor = (a: ClaimResponse) =>
    a.forrasok.some(
      (f) => f.tipus === 'labor' || (!!laborDokIds && f.tipus === 'dokumentum' && laborDokIds.has(f.dokumentumId)),
    );

  const count = (s: Szuro) =>
    s === 'mind'
      ? allitasok.length
      : s === 'labor'
        ? allitasok.filter(isLabor).length
        : allitasok.filter((a) => a.claimType === s).length;

  const lathato = useMemo(() => {
    const pass = (a: ClaimResponse) =>
      a.sulyossag === 'critical' || szuro === 'mind' || (szuro === 'labor' ? isLabor(a) : a.claimType === szuro);
    return allitasok.filter(pass).sort((a, b) => rang[a.sulyossag] - rang[b.sulyossag]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allitasok, szuro, laborDokIds]);

  const fuggo = allitasok.filter((a) => a.claimType === 'modell' && !a.jovahagyas).length;

  const chips: { key: Szuro; label: string; icon?: 'lock' | 'doc' | 'spark' | 'flask' }[] = [
    { key: 'mind', label: 'Mind' },
    { key: 'szabaly', label: 'Szabály', icon: 'lock' },
    { key: 'forras', label: 'Forrás', icon: 'doc' },
    { key: 'modell', label: 'AI', icon: 'spark' },
    ...(laborDokIds ? [{ key: 'labor' as const, label: 'Labor', icon: 'flask' as const }] : []),
  ];

  return (
    <section className="claims" aria-label="Állítások">
      <div className="claims__head">
        <h2 className="h2">Állítások</h2>
        <span className="meta">
          {allitasok.length} állítás
          {fuggo > 0 && (
            <>
              {' · '}
              <strong className="accent-strong">{fuggo} jóváhagyásra vár</strong>
            </>
          )}
        </span>
      </div>
      <div className="chips" role="group" aria-label="Szűrés típus szerint">
        {chips.map((c) => (
          <button
            key={c.key}
            type="button"
            className={`chip${szuro === c.key ? ' chip--on' : ''}`}
            aria-pressed={szuro === c.key}
            onClick={() => setSzuro(c.key)}
          >
            {c.icon && <Icon name={c.icon} size={14} />}
            {c.label} · {count(c.key)}
          </button>
        ))}
      </div>
      {lathato.map((a) => (
        <AllitasKartya
          key={a.id}
          allitas={a}
          activeForras={activeForras}
          onDecided={onDecided}
          defaultCollapsed={a.sulyossag === 'info' || !!a.jovahagyas}
        />
      ))}
      {lathato.length === 0 && <p className="empty">Nincs a szűrőnek megfelelő állítás.</p>}
      {lathato.some((a) => a.sulyossag === 'critical') && (
        <p className="meta center">Kritikus állítás nem csukható össze, és szűréskor is látható marad.</p>
      )}
    </section>
  );
}
