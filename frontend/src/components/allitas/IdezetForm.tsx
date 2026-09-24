import { useId, useState, type FormEvent } from 'react';
import { api } from '../../api';
import type { Forras, Sulyossag } from '../../api/types';
import { useAkcio } from '../../lib/useAkcio';
import { usePatient } from '../../patient/PatientContext';
import { Icon } from '../Icon';
import { Segmented } from '../Segmented';

/**
 * Kijelölt szövegrész rögzítése szó szerinti idézet-állításként (RETRIEVED_FACT).
 * A szöveg pontosan a kijelölt rész — a backend a forrással összeveti, így az
 * állítás nem lehet átfogalmazás. Bizonytalanság nincs (forrás, nem következtetés).
 */
export function IdezetForm({
  forras,
  szoveg,
  helyLeiras,
  onDone,
  onCancel,
}: {
  forras: Forras;
  szoveg: string;
  helyLeiras: string;
  onDone: () => void;
  onCancel: () => void;
}) {
  const { patient, frissit } = usePatient();
  const [cimke, setCimke] = useState('');
  const [sulyossag, setSulyossag] = useState<Sulyossag>('info');
  const { busy, error, run } = useAkcio();
  const cimkeId = useId();

  async function submit(e: FormEvent) {
    e.preventDefault();
    const ok = await run(async () => {
      await api.createIdezet({ patientId: patient.id, szoveg, cimke: cimke || null, sulyossag, forras });
      await frissit();
      return true;
    });
    if (ok) onDone();
  }

  return (
    <form className="quoteform" onSubmit={submit} aria-label="Idézet rögzítése állításként">
      <div className="row-baseline">
        <span className="tag tag--forras">
          <Icon name="doc" size={14} strokeWidth={2.4} />
          Forrás
        </span>
        <strong>Idézet rögzítése állításként</strong>
        <span className="meta">{helyLeiras}</span>
      </div>
      <blockquote className="quote">„{szoveg}”</blockquote>
      <div className="row-end">
        <div className="field grow">
          <label htmlFor={cimkeId}>
            Címke <span className="meta">(nem kötelező, pl. „allergia”, „gyógyszer”)</span>
          </label>
          <input
            id={cimkeId}
            className="input"
            maxLength={128}
            value={cimke}
            onChange={(e) => setCimke(e.target.value)}
          />
        </div>
        <div className="field">
          <span className="field__legend">Súlyosság</span>
          <Segmented
            label="Súlyosság"
            value={sulyossag}
            onChange={setSulyossag}
            options={[
              ['info', 'Info'],
              ['warning', 'Figyelmeztetés'],
              ['critical', 'Kritikus'],
            ]}
          />
        </div>
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <div className="actions">
        <button type="submit" className="btn btn--primary" disabled={busy}>
          <Icon name="check" strokeWidth={2.6} />
          {busy ? 'Rögzítés…' : 'Rögzítés állításként'}
        </button>
        <button type="button" className="btn" onClick={onCancel} disabled={busy}>
          Mégse
        </button>
      </div>
    </form>
  );
}
