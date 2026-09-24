import { useId, useState } from 'react';
import { api } from '../../api';
import { useCurrentUser } from '../../api/currentUser';
import type { ClaimResponse, Dontes, JovahagyasResponse } from '../../api/types';
import { formatIdo } from '../../lib/format';
import { Icon } from '../Icon';

/**
 * Human-in-the-loop sáv — CSAK `modell` típusú állításon jelenik meg.
 * A kártya része, nem elrejtett ikon: a döntés látható cselekvés.
 */
export function ApprovalBar({
  allitas,
  onDecided,
}: {
  allitas: ClaimResponse;
  onDecided: (j: JovahagyasResponse) => void;
}) {
  const [mode, setMode] = useState<'idle' | 'modositas' | 'elutasitas'>('idle');
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fieldId = useId();
  const user = useCurrentUser();

  if (allitas.claimType !== 'modell') return null;

  const j = allitas.jovahagyas;
  if (j) return <DecisionFooter j={j} />;

  // A döntés az orvosé: admin nem hagyhat jóvá (a backend is 409-cel utasítaná el)
  if (!user || user.role !== 'DOCTOR') {
    return (
      <div className="foot foot--modell">
        <span className="pending">
          <Icon name="clock" />
          Jóváhagyásra vár — dönteni orvosként belépve lehet
        </span>
      </div>
    );
  }

  async function submit(dontes: Dontes) {
    setBusy(true);
    setError(null);
    try {
      const res = await api.jovahagy(allitas.id, {
        dontes,
        modositottSzoveg: dontes === 'modositva' ? text.trim() : undefined,
      });
      onDecided(res);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

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
              Módosított állítás
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
            {modositas ? 'Módosítva jóváhagyom' : 'Elutasítom'}
          </button>
          <button type="button" className="btn" onClick={() => setMode('idle')} disabled={busy}>
            Mégse
          </button>
        </div>
      </form>
    );
  }

  return (
    <div className="foot foot--modell">
      <span className="pending">
        <Icon name="clock" />
        Jóváhagyásra vár
      </span>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="actions">
        <button className="btn btn--primary" onClick={() => void submit('jovahagyva')} disabled={busy}>
          <Icon name="check" strokeWidth={2.6} />
          Jóváhagyom
        </button>
        <button
          className="btn"
          onClick={() => {
            setText(allitas.cim);
            setMode('modositas');
          }}
          disabled={busy}
        >
          <Icon name="pencil" />
          Módosítom
        </button>
        <button
          className="btn"
          onClick={() => {
            setText('');
            setMode('elutasitas');
          }}
          disabled={busy}
        >
          <Icon name="x" strokeWidth={2.4} />
          Elutasítom
        </button>
      </div>
    </div>
  );
}

function DecisionFooter({ j }: { j: JovahagyasResponse }) {
  const meta = {
    jovahagyva: { icon: 'check' as const, label: 'Jóváhagyva' },
    modositva: { icon: 'pencil' as const, label: 'Módosítva és jóváhagyva' },
    elutasitva: { icon: 'x' as const, label: 'Elutasítva' },
  }[j.dontes];
  return (
    <div className={`foot foot--done foot--${j.dontes}`}>
      <span className="done">
        <Icon name={meta.icon} strokeWidth={2.6} />
        {meta.label}
      </span>
      <span className="foot__who">
        {j.felhasznaloNev} · {formatIdo(j.idopont) || j.idopont}
      </span>
    </div>
  );
}
