import { useState } from 'react';
import type { ClaimResponse, Forras } from '../../api/types';
import { formatDatumIdo } from '../../lib/format';
import { Icon } from '../Icon';
import { ApprovalBar } from './ApprovalBar';
import { JovahagyasElozmeny } from './JovahagyasElozmeny';
import { SeverityBand, SeverityInline, SourceChip, TypeTag, UncertaintyMeter } from './parts';

/**
 * Az Allitas-kártya. Biztonsági jelzés, nem díszítés:
 *  - típus: ikon + felirat + szegélystílus (szabaly: tömör, forras: vékony, modell: szaggatott)
 *  - súlyosság: sáv (critical/warning) vagy felirat (info)
 *  - modell: kötelező bizonytalanság + jóváhagyási sáv
 *  - critical nem csukható össze
 *  - HOGY és MIKOR: az előállító és a klinikai idő mindig olvasható
 */
export function AllitasKartya({
  allitas,
  activeForras,
  onDecided,
  defaultCollapsed = false,
}: {
  allitas: ClaimResponse;
  activeForras?: (f: Forras) => boolean;
  onDecided: (id: number) => Promise<void>;
  defaultCollapsed?: boolean;
}) {
  const a = allitas;
  const collapsible = a.sulyossag !== 'critical';
  const [collapsed, setCollapsed] = useState(collapsible && defaultCollapsed);
  const [elozmeny, setElozmeny] = useState(false);
  const isSelected = !!activeForras && a.forrasok.some(activeForras);
  const rejected = a.jovahagyas?.dontes === 'elutasitva';
  const modified = a.jovahagyas?.dontes === 'modositva' ? a.jovahagyas.modositottSzoveg : null;

  const classes = [
    'claim',
    `claim--${a.claimType}`,
    a.sulyossag === 'critical' && 'claim--critical',
    isSelected && 'claim--selected',
    a.jovahagyas && !rejected && 'claim--approved',
    rejected && 'claim--rejected',
  ]
    .filter(Boolean)
    .join(' ');

  const toggle = collapsible && (
    <button
      type="button"
      className="iconbtn iconbtn--ghost iconbtn--sm claim__toggle"
      aria-expanded={!collapsed}
      aria-label={collapsed ? 'Kártya kinyitása' : 'Kártya összecsukása'}
      onClick={() => setCollapsed((c) => !c)}
    >
      <Icon name={collapsed ? 'chevronDown' : 'chevronUp'} size={18} />
    </button>
  );

  if (collapsed) {
    return (
      <article id={`allitas-${a.id}`} className={`${classes} claim--collapsed`} aria-label={a.cim}>
        <div className="claim__row">
          <TypeTag type={a.claimType} />
          <span className="claim__row-title">{modified ?? a.cim}</span>
          {a.bizonytalansag && <UncertaintyMeter value={a.bizonytalansag} compact />}
          <SeverityInline sulyossag={a.sulyossag} />
          {a.claimType === 'modell' && !a.jovahagyas && <span className="pending pending--sm">· vár</span>}
          {toggle}
        </div>
      </article>
    );
  }

  return (
    <article id={`allitas-${a.id}`} className={classes} aria-label={a.cim}>
      <SeverityBand sulyossag={a.sulyossag} />
      <div className="claim__body">
        <div className="claim__head">
          <TypeTag type={a.claimType} />
          {a.claimType === 'modell' && a.bizonytalansag && <UncertaintyMeter value={a.bizonytalansag} />}
          {a.claimType === 'szabaly' && a.szabalyEredet && <span className="meta">{a.szabalyEredet}</span>}
          {a.claimType === 'forras' && <span className="meta">szó szerinti idézet</span>}
          {isSelected && <span className="claim__open">← megnyitva</span>}
          {a.sulyossag === 'info' && <SeverityInline sulyossag="info" />}
          {toggle}
        </div>
        {modified ? (
          <>
            <h3 className="claim__title">{modified}</h3>
            <p className="meta claim__orig">{a.cim}</p>
          </>
        ) : (
          <h3 className="claim__title">{a.cim}</h3>
        )}
        {a.szoveg && <p className="claim__text">{a.szoveg}</p>}
        {a.idezet && <blockquote className="quote">„{a.idezet}”</blockquote>}
        {a.forrasok.length > 0 && (
          <div className="srcs">
            {a.forrasok.map((f, i) => (
              <SourceChip key={i} forras={f} active={activeForras?.(f)} />
            ))}
          </div>
        )}
        <p className="meta claim__prov">
          #{a.id} · klinikai idő: {formatDatumIdo(a.klinikaiIdo)} · {a.eloallito}
        </p>
        {a.jovahagyas && (
          <div>
            <button
              type="button"
              className="linkbtn"
              aria-expanded={elozmeny}
              onClick={() => setElozmeny((e) => !e)}
            >
              <Icon name="history" size={14} />
              Döntési előzmény
            </button>
            {elozmeny && <JovahagyasElozmeny key={a.jovahagyas.idopont} allitasId={a.id} />}
          </div>
        )}
      </div>
      {a.claimType === 'szabaly' && (
        <div className="foot foot--szabaly">
          <Icon name="lock" size={14} strokeWidth={2.4} />
          Reprodukálható szabály — nincs jóváhagyandó következtetés
        </div>
      )}
      <ApprovalBar allitas={a} onDecided={() => onDecided(a.id)} />
    </article>
  );
}
