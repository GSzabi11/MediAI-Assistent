/** Szegmentált választó (néhány kizáró opció) — a labornézet és az űrlapok közös eleme. */
export function Segmented<T extends string>({
  label,
  value,
  onChange,
  options,
  disabled = false,
}: {
  label: string;
  value: T;
  onChange: (v: T) => void;
  options: readonly (readonly [T, string])[];
  disabled?: boolean;
}) {
  return (
    <div className="seg2" role="group" aria-label={label}>
      {options.map(([v, l]) => (
        <button
          key={v}
          type="button"
          className={value === v ? 'on' : ''}
          aria-pressed={value === v}
          onClick={() => onChange(v)}
          disabled={disabled}
        >
          {l}
        </button>
      ))}
    </div>
  );
}
