/** 订单详情：子单分组（拆单）、pay_deadline 倒计时、金额明细、时间线、取消/确认收货。 */

import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { orderApi } from '@/api/order';
import { useCountdown } from '@/hooks/useCountdown';
import { toast } from '@/store/toast';
import { formatCountdown, formatDateTime, formatPrice } from '@/utils/format';
import { isUnpaid } from '@/utils/orderStatus';
import type { OrderItemView } from '@/types/domain';

export function OrderDetailPage() {
  const { orderId } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const id = Number(orderId);

  const { data: order, isLoading } = useQuery({
    queryKey: ['order', id],
    queryFn: () => orderApi.detail(id),
    enabled: Number.isFinite(id),
  });

  const remain = useCountdown(order?.payDeadline);

  const cancel = useMutation({
    mutationFn: () => orderApi.cancel(id),
    onSuccess: () => {
      toast.success('订单已取消');
      queryClient.invalidateQueries({ queryKey: ['order', id] });
      queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
  });
  const confirmReceipt = useMutation({
    mutationFn: () => orderApi.confirmReceipt(id),
    onSuccess: () => {
      toast.success('已确认收货');
      queryClient.invalidateQueries({ queryKey: ['order', id] });
      queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
  });

  if (isLoading || !order) return <p className="py-20 text-center text-gray-400">{isLoading ? '加载中…' : '订单不存在'}</p>;

  // 子单分组：children 为服务端拆单结果，行按 storeId 归组
  const children = order.children ?? [];
  const groupByStore = children.length > 0;

  return (
    <div className="flex flex-col gap-4">
      <div className={`rounded-lg p-6 text-white ${isUnpaid(order.status) ? 'bg-gradient-to-r from-orange-400 to-red-400' : 'bg-gradient-to-r from-gray-700 to-gray-900'}`}>
        <div className="flex items-center gap-3">
          <span className={`rounded bg-white/20 px-2 py-0.5 text-xs`}>{order.statusText}</span>
          {isUnpaid(order.status) && remain > 0 && (
            <span className="text-sm">支付剩余 {formatCountdown(remain)}</span>
          )}
        </div>
        <p className="mt-3 text-2xl font-bold">{formatPrice(order.amountDetail.payAmount)}</p>
        <p className="mt-1 text-xs text-white/70">订单号 {order.orderNo}</p>
      </div>

      {isUnpaid(order.status) && (
        <div className="flex justify-end gap-3">
          <button
            type="button"
            disabled={cancel.isPending}
            onClick={() => window.confirm('确定取消该订单吗？') && cancel.mutate()}
            className="rounded border border-gray-300 bg-white px-6 py-2 text-sm text-gray-600 hover:border-gray-400"
          >
            取消订单
          </button>
          <button
            type="button"
            onClick={() => navigate(`/pay/${id}`)}
            className="rounded bg-red-500 px-6 py-2 text-sm text-white hover:bg-red-600"
          >
            去支付
          </button>
        </div>
      )}
      {order.status === 2 && (
        <div className="flex justify-end">
          <button
            type="button"
            disabled={confirmReceipt.isPending}
            onClick={() => window.confirm('确认已收到货？') && confirmReceipt.mutate()}
            className="rounded bg-red-500 px-6 py-2 text-sm text-white hover:bg-red-600"
          >
            确认收货
          </button>
        </div>
      )}

      {/* 收货信息 */}
      <section className="rounded-lg border border-gray-200 bg-white p-4 text-sm">
        <h2 className="mb-2 font-semibold text-gray-700">收货信息</h2>
        <p className="text-gray-600">
          {order.receiverName} {order.receiverMobile}
        </p>
        <p className="mt-1 text-gray-500">{order.receiverAddress}</p>
        {order.remark && <p className="mt-2 text-xs text-gray-400">买家备注：{order.remark}</p>}
      </section>

      {/* 商品（按子单/店铺分组） */}
      <section className="rounded-lg border border-gray-200 bg-white">
        {groupByStore
          ? children.map((child) => (
              <div key={child.orderId}>
                <p className="border-b border-gray-100 bg-gray-50 px-4 py-2 text-sm text-gray-500">
                  子单 {child.orderNo} · 店铺 {child.storeId ?? '-'} · {child.itemCount} 件 ·
                  应付 <span className="text-red-600">{formatPrice(child.payAmount)}</span>
                </p>
                {order.items
                  .filter((i) => i.storeId === child.storeId)
                  .map((item) => (
                    <OrderRow key={`${item.orderId}-${item.skuId}`} item={item} />
                  ))}
              </div>
            ))
          : order.items.map((item) => <OrderRow key={`${item.orderId}-${item.skuId}`} item={item} />)}
      </section>

      {/* 金额明细（以接口返回为准） */}
      <section className="rounded-lg border border-gray-200 bg-white p-4 text-sm">
        <h2 className="mb-3 font-semibold text-gray-700">金额明细</h2>
        <div className="flex flex-col items-end gap-1.5 text-gray-600">
          <p>商品金额：{formatPrice(order.amountDetail.goodsAmount)}</p>
          <p>运费：{formatPrice(order.amountDetail.freightAmount)}</p>
          <p>优惠：-{formatPrice(order.amountDetail.discountAmount)}</p>
          <p className="text-base">
            实付：<span className="font-bold text-red-600">{formatPrice(order.amountDetail.payAmount)}</span>
          </p>
        </div>
      </section>

      {/* 时间线 */}
      {order.timeline.length > 0 && (
        <section className="rounded-lg border border-gray-200 bg-white p-4 text-sm">
          <h2 className="mb-3 font-semibold text-gray-700">订单动态</h2>
          <ol className="flex flex-col gap-2 border-l border-gray-200 pl-4 text-gray-600">
            {order.timeline.map((t, i) => (
              <li key={i} className="relative">
                <span className="absolute -left-[21px] top-1.5 h-2 w-2 rounded-full bg-red-400" />
                <span className="text-gray-800">{t.toStatusText}</span>
                {t.reason && <span className="ml-2 text-xs text-gray-400">（{t.reason}）</span>}
                <span className="ml-2 text-xs text-gray-400">{formatDateTime(t.time)}</span>
              </li>
            ))}
          </ol>
        </section>
      )}

      <div className="text-center">
        <Link to="/orders" className="text-sm text-red-500 hover:underline">
          ← 返回订单列表
        </Link>
      </div>
    </div>
  );
}

function OrderRow({ item }: { item: OrderItemView }) {
  return (
    <div className="flex items-center gap-4 border-b border-gray-100 px-4 py-3 last:border-b-0">
      <div className="h-16 w-16 shrink-0 overflow-hidden rounded bg-gray-100">
        <img src={item.skuImage} alt="" className="h-full w-full object-cover" />
      </div>
      <p className="line-clamp-2 flex-1 text-sm text-gray-700">{item.skuName}</p>
      <span className="w-20 text-right text-sm text-gray-600">{formatPrice(item.price)}</span>
      <span className="w-10 text-center text-sm text-gray-400">×{item.quantity}</span>
      <span className="w-24 text-right text-sm text-red-600">{formatPrice(item.totalAmount)}</span>
    </div>
  );
}
