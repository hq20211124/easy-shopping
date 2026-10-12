/** 购物车：单选/全选、数量步进、失效商品区、选中合计（金额展示以接口汇总为准）。 */

import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { cartApi } from '@/api/cart';
import { QuantityStepper } from '@/components/common/QuantityStepper';
import { useCheckoutStore } from '@/store/checkout';
import { toast } from '@/store/toast';
import { formatPrice, lineTotalText } from '@/utils/format';
import { lineTotal } from '@/utils/amount';

export function CartPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const startCartCheckout = useCheckoutStore((s) => s.startCartCheckout);
  const [checked, setChecked] = useState<Set<number> | null>(null); // null = 用服务端勾选态

  const { data, isLoading } = useQuery({ queryKey: ['cart', 'list'], queryFn: cartApi.list });
  const items = data?.items ?? [];
  const valid = items.filter((i) => !i.invalid);
  const invalid = items.filter((i) => i.invalid);

  // 勾选态：优先本地即时反馈，写接口成功后由服务端回填刷新
  const selectedIds = useMemo(() => {
    if (checked) return [...checked].filter((id) => valid.some((v) => v.cartItemId === id));
    return valid.filter((v) => v.selected).map((v) => v.cartItemId);
  }, [checked, valid]);

  const selectedItems = valid.filter((v) => selectedIds.includes(v.cartItemId));
  const selectedAmount = selectedItems.reduce((sum, i) => sum + lineTotal(i.price, i.quantity), 0);
  const allSelected = valid.length > 0 && selectedIds.length === valid.length;

  const refreshCart = () => queryClient.invalidateQueries({ queryKey: ['cart'] });

  const updateQty = useMutation({
    mutationFn: ({ cartItemId, quantity }: { cartItemId: number; quantity: number }) =>
      cartApi.update(cartItemId, { quantity }),
    onSuccess: refreshCart,
  });

  const toggleOne = useMutation({
    mutationFn: ({ cartItemId, selected }: { cartItemId: number; selected: boolean }) =>
      cartApi.update(cartItemId, { selected }),
    onSuccess: refreshCart,
  });

  const toggleAll = useMutation({
    mutationFn: (selected: boolean) => cartApi.select(null, selected),
    onSuccess: refreshCart,
  });

  const removeItems = useMutation({
    mutationFn: (ids: number[]) => cartApi.remove(ids),
    onSuccess: () => {
      toast.success('删除成功');
      refreshCart();
    },
  });

  const onToggleOne = (cartItemId: number, selected: boolean) => {
    setChecked(new Set(selectedIds));
    toggleOne.mutate({ cartItemId, selected });
  };

  const onToggleAll = (selected: boolean) => {
    setChecked(selected ? new Set(valid.map((v) => v.cartItemId)) : new Set());
    toggleAll.mutate(selected);
  };

  const onCheckout = () => {
    if (selectedIds.length === 0) {
      toast.info('请先勾选要结算的商品');
      return;
    }
    startCartCheckout(selectedIds);
    navigate('/checkout');
  };

  if (isLoading) return <p className="py-20 text-center text-gray-400">加载中…</p>;

  if (items.length === 0) {
    return (
      <div className="py-24 text-center text-gray-400">
        <p className="text-lg">购物车还是空的</p>
        <Link to="/" className="mt-3 inline-block text-sm text-red-500 hover:underline">
          去逛逛 →
        </Link>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-lg font-semibold text-gray-800">购物车（{data?.totalQuantity ?? 0} 件）</h1>

      <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
        <div className="flex items-center gap-4 border-b border-gray-100 px-4 py-3 text-sm text-gray-500">
          <label className="flex cursor-pointer items-center gap-2">
            <input type="checkbox" checked={allSelected} onChange={(e) => onToggleAll(e.target.checked)} />
            全选
          </label>
          <span className="ml-auto">单价 / 数量 / 小计</span>
        </div>
        {valid.map((item) => {
          const selectedRow = selectedIds.includes(item.cartItemId);
          return (
            <div key={item.cartItemId} className="flex items-center gap-4 border-b border-gray-100 px-4 py-4">
              <input
                type="checkbox"
                checked={selectedRow}
                onChange={(e) => onToggleOne(item.cartItemId, e.target.checked)}
              />
              <Link to={`/product/${item.productId}`} className="h-20 w-20 shrink-0 overflow-hidden rounded bg-gray-100">
                <img src={item.mainImage} alt={item.title} className="h-full w-full object-cover" />
              </Link>
              <div className="min-w-0 flex-1">
                <p className="line-clamp-2 text-sm text-gray-800">{item.title}</p>
                {!item.inStock && <p className="mt-1 text-xs text-red-500">库存紧张</p>}
              </div>
              <span className="w-24 text-center text-sm text-gray-600">{formatPrice(item.price)}</span>
              <QuantityStepper
                value={item.quantity}
                onChange={(quantity) => updateQty.mutate({ cartItemId: item.cartItemId, quantity })}
                max={item.stock}
              />
              <span className="w-24 text-right text-sm font-semibold text-red-600">{lineTotalText(item.price, item.quantity)}</span>
              <button
                type="button"
                onClick={() => removeItems.mutate([item.cartItemId])}
                className="w-12 text-right text-xs text-gray-400 hover:text-red-500"
              >
                删除
              </button>
            </div>
          );
        })}
      </div>

      {invalid.length > 0 && (
        <div className="overflow-hidden rounded-lg border border-dashed border-gray-300 bg-white">
          <div className="flex items-center justify-between border-b border-gray-100 px-4 py-3">
            <span className="text-sm font-semibold text-gray-500">失效商品（{invalid.length}）</span>
            <button
              type="button"
              onClick={() => removeItems.mutate(invalid.map((i) => i.cartItemId))}
              className="text-xs text-gray-400 hover:text-red-500"
            >
              清除失效商品
            </button>
          </div>
          {invalid.map((item) => (
            <div key={item.cartItemId} className="flex items-center gap-4 px-4 py-4 opacity-60">
              <div className="h-16 w-16 shrink-0 overflow-hidden rounded bg-gray-100 grayscale">
                <img src={item.mainImage} alt="" className="h-full w-full object-cover" />
              </div>
              <p className="line-clamp-2 flex-1 text-sm text-gray-500">{item.title}</p>
              <span className="text-xs text-gray-400">商品已下架</span>
            </div>
          ))}
        </div>
      )}

      <div className="sticky bottom-0 z-10 flex items-center gap-6 rounded-lg border border-gray-200 bg-white px-6 py-4 shadow">
        <label className="flex cursor-pointer items-center gap-2 text-sm text-gray-600">
          <input type="checkbox" checked={allSelected} onChange={(e) => onToggleAll(e.target.checked)} />
          全选
        </label>
        <span className="text-sm text-gray-500">
          已选 <span className="text-red-600">{selectedItems.reduce((s, i) => s + i.quantity, 0)}</span> 件
        </span>
        <span className="ml-auto text-sm text-gray-500">
          合计：
          <span className="text-xl font-bold text-red-600">{formatPrice(selectedAmount)}</span>
        </span>
        <button
          type="button"
          onClick={onCheckout}
          disabled={selectedIds.length === 0}
          className="h-10 rounded-full bg-red-500 px-10 text-sm font-semibold text-white hover:bg-red-600 disabled:bg-red-200"
        >
          结算
        </button>
      </div>
      <p className="text-right text-xs text-gray-400">应付金额以结算页与下单结果的服务端计算为准</p>
    </div>
  );
}
