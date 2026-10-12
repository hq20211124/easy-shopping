/** 数量步进器：min=1，max 可空（空则仅阻止 <1）。 */

interface Props {
  value: number;
  onChange: (v: number) => void;
  max?: number;
  disabled?: boolean;
}

export function QuantityStepper({ value, onChange, max, disabled }: Props) {
  const clamp = (v: number) => {
    let next = Math.max(1, Math.floor(v || 1));
    if (max !== undefined) next = Math.min(next, max);
    return next;
  };
  return (
    <div className="inline-flex items-center overflow-hidden rounded border border-gray-300">
      <button
        type="button"
        disabled={disabled || value <= 1}
        onClick={() => onChange(clamp(value - 1))}
        className="h-8 w-8 text-gray-600 hover:bg-gray-100 disabled:cursor-not-allowed disabled:text-gray-300"
      >
        −
      </button>
      <input
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(clamp(Number(e.target.value)))}
        className="h-8 w-12 border-x border-gray-300 text-center text-sm outline-none"
      />
      <button
        type="button"
        disabled={disabled || (max !== undefined && value >= max)}
        onClick={() => onChange(clamp(value + 1))}
        className="h-8 w-8 text-gray-600 hover:bg-gray-100 disabled:cursor-not-allowed disabled:text-gray-300"
      >
        +
      </button>
    </div>
  );
}
