/** 结算草稿：来源「购物车勾选」或「详情页立即购买」，进入结算页前写入。 */

import { create } from 'zustand';

export interface BuyNowDraft {
  spuId: number;
  skuId: number;
  quantity: number;
  title: string;
  image: string;
  price: string | number;
  specText: string;
}

interface CheckoutState {
  /** cart=购物车结算；buyNow=立即购买 */
  mode: 'cart' | 'buyNow';
  cartItemIds: number[];
  buyNow: BuyNowDraft | null;
  couponId: number | null;
  addressId: number | null;
  startBuyNow: (draft: BuyNowDraft) => void;
  startCartCheckout: (cartItemIds: number[]) => void;
  setCoupon: (id: number | null) => void;
  setAddress: (id: number | null) => void;
  reset: () => void;
}

export const useCheckoutStore = create<CheckoutState>((set) => ({
  mode: 'cart',
  cartItemIds: [],
  buyNow: null,
  couponId: null,
  addressId: null,
  startBuyNow: (draft) => set({ mode: 'buyNow', buyNow: draft, cartItemIds: [], couponId: null, addressId: null }),
  startCartCheckout: (cartItemIds) => set({ mode: 'cart', cartItemIds, buyNow: null, couponId: null, addressId: null }),
  setCoupon: (id) => set({ couponId: id }),
  setAddress: (id) => set({ addressId: id }),
  reset: () => set({ mode: 'cart', cartItemIds: [], buyNow: null, couponId: null, addressId: null }),
}));
