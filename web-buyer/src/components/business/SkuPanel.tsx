/** SKU 规格矩阵选择器：选中/禁售态联动逻辑在 utils/sku.ts（纯函数，含单测）。 */

import type { ProductSpec, ProductSku } from '@/types/domain';
import { buildSkuOptions, toggleOption, type SelectedSpecs } from '@/utils/sku';

interface Props {
  specs: ProductSpec[];
  skus: ProductSku[];
  selected: SelectedSpecs;
  onChange: (next: SelectedSpecs) => void;
}

export function SkuPanel({ specs, skus, selected, onChange }: Props) {
  if (specs.length === 0) return null;
  return (
    <div className="flex flex-col gap-4">
      {buildSkuOptions(specs, skus, selected).map((group) => (
        <div key={group.name} className="flex items-start gap-3">
          <span className="w-12 shrink-0 pt-1.5 text-sm text-gray-500">{group.name}</span>
          <div className="flex flex-wrap gap-2">
            {group.options.map((opt) => (
              <button
                key={opt.value}
                type="button"
                disabled={!opt.enabled}
                onClick={() => onChange(toggleOption(selected, group.name, opt.value))}
                className={[
                  'rounded border px-3 py-1.5 text-sm transition-colors',
                  opt.selected
                    ? 'border-red-500 bg-red-50 text-red-600'
                    : opt.enabled
                      ? 'border-gray-300 text-gray-700 hover:border-red-400'
                      : 'cursor-not-allowed border-gray-200 bg-gray-100 text-gray-300 line-through',
                ].join(' ')}
                title={opt.enabled ? undefined : '该规格组合暂不可售'}
              >
                {opt.value}
              </button>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
