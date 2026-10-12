/** 结算页：地址选择 + 券选择（preview 失败降级）+ 明细（商品/运费/优惠/应付）→ 提交订单。 */

import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { cartApi } from '@/api/cart';
import { couponApi } from '@/api/coupon';
import { orderApi } from '@/api/order';
import { userApi } from '@/api/user';
import { lineTotal } from '@/utils/amount';
import { formatAmount, formatPrice } from '@/utils/format';
import { toast } from '@/store/toast';
import { useCheckoutStore } from '@/store/checkout';
import type { Address } from '@/types/domain';

export function CheckoutPage() {
  const navigate = useNavigate();
  const { mode, cartItemIds, buyNow, couponId, setCoupon, addressId, setAddress } = useCheckoutStore();
  const [remark, setRemark] = useState('');

  const { data: cart } = useQuery({
    queryKey: ['cart', 'list'],
    queryFn: cartApi.list,
    enabled: mode === 'cart',
  });
  const { data: addresses } = useQuery({ queryKey: ['addresses'], queryFn: userApi.listAddresses });
  const { data: coupons } = useQuery({ queryKey: ['coupons', 'my'], queryFn: () => couponApi.my() });

  // 结算行：购物车模式取勾选行；立即购买取草稿（金额为展示口径，下单以服务端权威计价为准）
  const rows = useMemo(() => {
    if (mode === 'buyNow') {
      if (!buyNow) return [];
      return [{ skuId: buyNow.skuId, quantity: buyNow.quantity, title: buyNow.title, image: buyNow.image, price: buyNow.price }];
    }
    return (cart?.items ?? [])
      .filter((i) => !i.invalid && i.selected && cartItemIds.includes(i.cartItemId))
      .map((i) => ({ skuId: i.skuId, quantity: i.quantity, title: i.title, image: i.mainImage, price: i.price }));
  }, [mode, buyNow, cart, cartItemIds]);

  const goodsAmount = rows.reduce((sum, r) => sum + lineTotal(r.price, r.quantity), 0);
  const freight = 0; // P0 运费恒 0（core-order README §3）

  // 券试算：选中券调 promotion preview；接口失败/不可用降级为无优惠继续结算
  const availableCoupons = (coupons ?? []).filter((c) => c.status === 'available');
  const { data: preview, isFetching: previewLoading } = useQuery({
    queryKey: ['coupon-preview', couponId, rows.map((r) => `${r.skuId}x${r.quantity}`).join(','), goodsAmount],
    queryFn: async () => {
      try {
        return await couponApi.preview(couponId!, rows.map((r) => lineTotal(r.price, r.quantity)));
      } catch {
        // 促销服务不可用 → 降级：不影响下单（以服务端下单计算为准）
        toast.info('优惠试算暂不可用，已按无优惠结算');
        return null;
      }
    },
    enabled: couponId !== null && rows.length > 0,
    retry: false,
  });
  const couponUsable = !!preview && preview.eligible;
  const discount = couponUsable ? Number(preview.discount) : 0;
  const payable = Math.max(0, goodsAmount + freight - discount);

  // 默认地址
  const selectedAddress: Address | undefined =
    addresses?.find((a) => a.id === addressId) ?? addresses?.find((a) => a.isDefault) ?? addresses?.[0];

  const submit = useMutation({
    mutationFn: () =>
      orderApi.create({
        items: rows.map((r) => ({ skuId: r.skuId, quantity: r.quantity })),
        addressId: selectedAddress?.id,
        receiverName: selectedAddress?.name,
        receiverMobile: selectedAddress?.mobile,
        receiverAddress: `${selectedAddress?.province}${selectedAddress?.city}${selectedAddress?.district ?? ''} ${selectedAddress?.detail}`,
        couponId: couponUsable ? couponId! : undefined,
        remark: remark.trim() || undefined,
      }),
    onSuccess: (order) => {
      toast.success('下单成功，请支付');
      navigate(`/pay/${order.orderId}`, { replace: true });
    },
  });

  if (rows.length === 0) {
    return (
      <div className="py-24 text-center text-gray-400">
        <p>没有可结算的商品</p>
        <Link to="/cart" className="mt-2 inline-block text-sm text-red-500 hover:underline">
          回购物车
        </Link>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-lg font-semibold text-gray-800">确认订单</h1>

      {/* 收货地址 */}
      <section className="rounded-lg border border-gray-200 bg-white p-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-gray-700">收货地址</h2>
          <Link to="/address" className="text-xs text-red-500 hover:underline">
            管理地址
          </Link>
        </div>
        {(addresses ?? []).length === 0 ? (
          <p className="text-sm text-gray-400">
            暂无地址，<Link to="/address" className="text-red-500 hover:underline">去新增</Link>
          </p>
        ) : (
          <div className="flex flex-wrap gap-3">
            {(addresses ?? []).map((a) => (
              <button
                key={a.id}
                type="button"
                onClick={() => setAddress(a.id)}
                className={`w-64 rounded border p-3 text-left text-sm ${
                  selectedAddress?.id === a.id ? 'border-red-500 bg-red-50' : 'border-gray-200 hover:border-red-300'
                }`}
              >
                <p className="font-semibold text-gray-800">
                  {a.name} <span className="ml-1 font-normal text-gray-500">{a.mobile}</span>
                  {a.isDefault && <span className="ml-1 rounded bg-red-100 px-1 text-xs text-red-500">默认</span>}
                </p>
                <p className="mt-1 line-clamp-2 text-xs text-gray-500">
                  {a.province}
                  {a.city}
                  {a.district ?? ''} {a.detail}
                </p>
              </button>
            ))}
          </div>
        )}
      </section>

      {/* 商品清单 */}
      <section className="rounded-lg border border-gray-200 bg-white">
        {rows.map((r) => (
          <div key={r.skuId} className="flex items-center gap-4 border-b border-gray-100 px-4 py-3 last:border-b-0">
            <div className="h-14 w-14 shrink-0 overflow-hidden rounded bg-gray-100">
              <img src={r.image} alt="" className="h-full w-full object-cover" />
            </div>
            <p className="line-clamp-2 flex-1 text-sm text-gray-700">{r.title}</p>
            <span className="w-20 text-sm text-gray-500">{formatPrice(r.price)}</span>
            <span className="w-12 text-center text-sm text-gray-500">×{r.quantity}</span>
            <span className="w-24 text-right text-sm text-red-600">{formatPrice(lineTotal(r.price, r.quantity))}</span>
          </div>
        ))}
      </section>

      {/* 优惠券 */}
      <section className="rounded-lg border border-gray-200 bg-white p-4">
        <h2 className="mb-3 text-sm font-semibold text-gray-700">优惠券</h2>
        {availableCoupons.length === 0 ? (
          <p className="text-sm text-gray-400">暂无可用优惠券</p>
        ) : (
          <div className="flex flex-wrap gap-3">
            <button
              type="button"
              onClick={() => setCoupon(null)}
              className={`rounded border px-3 py-2 text-sm ${couponId === null ? 'border-red-500 bg-red-50 text-red-600' : 'border-gray-200 text-gray-600'}`}
            >
              不使用优惠券
            </button>
            {availableCoupons.map((c) => (
              <button
                key={c.couponId}
                type="button"
                onClick={() => setCoupon(c.couponId)}
                className={`rounded border px-3 py-2 text-sm ${
                  couponId === c.couponId ? 'border-red-500 bg-red-50 text-red-600' : 'border-gray-200 text-gray-600'
                }`}
              >
                {c.name}
              </button>
            ))}
            {previewLoading && <span className="self-center text-xs text-gray-400">试算中…</span>}
            {couponId !== null && preview && !preview.eligible && (
              <span className="self-center text-xs text-gray-400">不可用：{preview.reason}（已按无优惠结算）</span>
            )}
          </div>
        )}
      </section>

      {/* 备注 */}
      <section className="rounded-lg border border-gray-200 bg-white p-4">
        <h2 className="mb-3 text-sm font-semibold text-gray-700">订单备注</h2>
        <input
          value={remark}
          onChange={(e) => setRemark(e.target.value)}
          maxLength={200}
          placeholder="选填，给商家留言"
          className="h-9 w-full rounded border border-gray-300 px-3 text-sm outline-none focus:border-red-400"
        />
      </section>

      {/* 明细与提交 */}
      <div className="sticky bottom-0 z-10 rounded-lg border border-gray-200 bg-white px-6 py-4 shadow">
        <div className="flex flex-col items-end gap-1 text-sm text-gray-600">
          <p>
            商品金额：<span className="text-gray-800">{formatPrice(goodsAmount)}</span>
          </p>
          <p>
            运费：<span className="text-gray-800">{formatPrice(freight)}</span>
          </p>
          <p>
            优惠：<span className="text-red-600">-{formatAmount(discount)}</span>
          </p>
        </div>
        <div className="mt-3 flex items-center justify-end gap-6">
          <span className="text-sm text-gray-500">
            应付：<span className="text-xl font-bold text-red-600">{formatPrice(payable)}</span>
          </span>
          <button
            type="button"
            disabled={submit.isPending || !selectedAddress}
            onClick={() => submit.mutate()}
            className="h-10 rounded-full bg-red-500 px-10 text-sm font-semibold text-white hover:bg-red-600 disabled:bg-red-200"
          >
            {submit.isPending ? '提交中…' : '提交订单'}
          </button>
        </div>
        <p className="mt-2 text-right text-xs text-gray-400">实际金额以下单结果的服务端计算为准</p>
      </div>
    </div>
  );
}
