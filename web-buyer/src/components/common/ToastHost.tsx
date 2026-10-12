/** 全局 Toast 容器（固定顶部居中）。 */

import { useToastStore } from '@/store/toast';

const toneClass: Record<string, string> = {
  error: 'bg-red-600 text-white',
  success: 'bg-green-600 text-white',
  info: 'bg-gray-800 text-white',
};

export function ToastHost() {
  const items = useToastStore((s) => s.items);
  const remove = useToastStore((s) => s.remove);
  if (items.length === 0) return null;
  return (
    <div className="pointer-events-none fixed left-1/2 top-16 z-50 flex -translate-x-1/2 flex-col items-center gap-2">
      {items.map((t) => (
        <button
          key={t.id}
          type="button"
          onClick={() => remove(t.id)}
          className={`pointer-events-auto max-w-xl rounded-lg px-5 py-3 text-sm shadow-lg ${toneClass[t.tone]}`}
        >
          {t.text}
        </button>
      ))}
    </div>
  );
}
