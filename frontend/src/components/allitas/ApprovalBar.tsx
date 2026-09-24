import { useId, useState, type ReactNode } from 'react';
import { api } from '../../api';
import { useCurrentUser } from '../../api/currentUser';
import type { ClaimResponse, Dontes, JovahagyasResponse } from '../../api/types';
import { formatDatumIdo } from '../../lib/format';
import { useAkcio } from '../../lib/useAkcio';
import { Icon } from '../Icon';

/**
 * Human-in-the-loop sáv — `modell` és `forras` állításon. A kártya része, nem
 * elrejtett ikon: a döntés látható cselekvés.
 *  - modell: jóváhagyás kötelező (AI-következtetés)
 *  - forras: megerősítés — enélkül nem lehet gyógyszer/allergia forrása
 *  - szabaly: nincs sáv (reprodukálható, nincs jóváhagyandó következtetés)
 * Döntés után új döntés hozható: a backend az előzményt megőrzi, a legfrissebb érvényes.
 */
export function ApprovalBar({ allitas, onDecided }: { allitas: ClaimResponse; onDecided: () => Promise<void> }) {
  const [mode, setMode] = useState<'idle' | 'modositas' | 'elutasitas'>('idle');
  const [ujDontes, setUjDontes] = useState(false);
  const [text, setText] = useState('');
  const { busy, error, run } = useAkcio();
  const fieldId = useId();
  const user = useCurrentUser();

  if (allitas.claimType === 'szabaly') return null;
  const forras = allitas.claimType === 'forras';
  const felirat = forras
    ? { var: 'Nincs megerősítve', ok: 'Megerősítem', mod: 'Javítom', modOk: 'Javítva megerősítem' }
    : { var: 'Jóváhagyásra vár', ok: 'Jóváhagyom', mod: 'Módosítom', modOk: 'Módosítva jóváhagyom' };
  const orvos = user?.role === 'DOCTOR';

  const j = allitas.jovahagyas;
  if (j && !ujDontes) {
    return (
      <DecisionFooter j={j}>
        {orvos && (
          <button type="button" className="btn btn--sm btn--ghost-inv" onClick={() => setUjDontes(true)}>
            Új döntés
          </button>
        )}
      </DecisionFooter>
    );
  }

  // A döntés az orvosé: admin nem hagyhat jóvá (a backend is 409-cel utasítaná el)
  if (!user || !orvos) {
    return (
      <div className="foot foot--modell">
        <span className="pending">
          <Icon name="clock" />
          {felirat.var} — dönteni orvosként belépve lehet
        </span>
      </div>
    );
  }

  async function submit(dontes: Dontes) {
    const ok = await run(async () => {
      await api.jovahagy(allitas.id, {
        dontes,
        modositottSzoveg: dontes === 'modositva' ? text.trim() : undefined,
      });
      // Az érvényes állapotot a backend számolja (legfrissebb döntés) — onnan kérjük vissza
      await onDecided();
      return true;
    });
    if (ok) {
      setMode('idle');
      setUjDontes(false);
    }
  }

  const megse = ujDontes ? (
    <button type="button" className="btn" onClick={() => setUjDontes(false)} disabled={busy}>
      Mégse
    </button>
  ) : null;

  if (mode !== 'idle') {
    const modositas = mode === 'modositas';
    return (
      <form
        className="foot foot--modell foot--form"
        onSubmit={(e) => {
          e.preventDefault();
          void submit(modositas ? 'modositva' : 'elutasitva');
        }}
      >
        {modositas ? (
          <>
            <label htmlFor={fieldId} className="foot__label">
              {forras ? 'Javított szöveg' : 'Módosított állítás'}
            </label>
            <textarea
              id={fieldId}
              className="textarea"
              rows={2}
              value={text}
              onChange={(e) => setText(e.target.value)}
              required
              autoFocus
            />
          </>
        ) : (
          <p className="foot__label">Biztosan elutasítod ezt az állítást? ({user.username} nevében)</p>
        )}
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <div className="actions">
          <button type="submit" className="btn btn--primary" disabled={busy || (modositas && !text.trim())}>
            <Icon name={modositas ? 'check' : 'x'} strokeWidth={2.6} />
            {modositas ? felirat.modOk : 'Elutasítom'}
          </button>
          <button type="button" className="btn" onClick={() => setMode('idle')} disabled={busy}>
            Vissza
          </button>
        </div>
      </form>
    );
  }

  return (
    <div className="foot foot--modell">
      <span className="pending">
        <Icon name="clock" />
        {ujDontes ? 'Új döntés' : felirat.var}
      </span>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="actions">
        <button className="btn btn--primary" onClick={() => void submit('jovahagyva')} disabled={busy}>
          <Icon name="check" strokeWidth={2.6} />
          {felirat.ok}
        </button>
        <button
          className="btn"
          onClick={() => {
            setText(allitas.ervenyesSzoveg || allitas.idezet || allitas.cim);
            setMode('modositas');
          }}
          disabled={busy}
        >
          <Icon name="pencil" />
          {felirat.mod}
        </button>
        <button className="btn" onClick={() => setMode('elutasitas')} disabled={busy}>
          <Icon name="x" strokeWidth={2.4} />
          Elutasítom
        </button>
        {megse}
      </div>
    </div>
  );
}

function DecisionFooter({ j, children }: { j: JovahagyasResponse; children?: ReactNode }) {
  const meta = {
    jovahagyva: { icon: 'check' as const, label: 'Jóváhagyva' },
    modositva: { icon: 'pencil' as const, label: 'Módosítva és jóváhagyva' },
    elutasitva: { icon: 'x' as const, label: 'Elutasítva' },
  }[j.dontes];
  return (
    <div className={`foot foot--done foot--${j.dontes}`}>
      <div className="row-baseline foot__done-row">
        <span className="done">
          <Icon name={meta.icon} strokeWidth={2.6} />
          {meta.label}
        </span>
        <span className="push-right">{children}</span>
      </div>
      <span className="foot__who">
        {j.felhasznaloNev} · {formatDatumIdo(j.idopont)}
      </span>
    </div>
  );
}
