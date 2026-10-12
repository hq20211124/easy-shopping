/** 全局 Toast：API 层业务错误（3002 库存不足/券失效等）统一以响应 msg 提示。 */

import { create } from 'zustand';

export type ToastTone = 'error' | 'success' | 'info';

export interface ToastItem {
  id: number;
  text: string;
  tone: ToastTone;
}

interface ToastState {
  items: ToastItem[];
  push: (text: string, tone: ToastTone) => void;
  remove: (id: number) => void;
}

let seq = 0;

export const useToastStore = create<ToastState>((set) => ({
  items: [],
  push: (text, tone) => {
    const id = ++seq;
    set((s) => ({ items: [...s.items, { id, text, tone }] }));
    setTimeout(() => set((s) => ({ items: s.items.filter((t) => t.id !== id) })), 3000);
  },
  remove: (id) => set((s) => ({ items: s.items.filter((t) => t.id !== id) })),
}));

export const toast = {
  error: (text: string) => useToastStore.getState().push(text, 'error'),
  success: (text: string) => useToastStore.getState().push(text, 'success'),
  info: (text: string) => useToastStore.getState().push(text, 'info'),
};
