/** 订单列表：0 基 8 值状态 Tab（映射见 utils/orderStatus.ts）+ pay_deadline 倒计时 + 操作。 */

import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { orderApi } from '@/api/order';
import { useCountdown } from '@/hooks/useCountdown';
import { toast } from '@/store/toast';
import { formatCountdown, formatDateTime, formatPrice } from '@/utils/format';
import { ORDER_TABS, isUnpaid, statusTone } from '@/utils/orderStatus';
import type { OrderListVO } from '@/types/domain';

export function OrderListPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<number | undefined>(undefined);
  const [page, setPage] = useState(1);
  const pageSize = 10;

  const { data, isLoading } = useQuery({
    queryKey: ['orders', 'list', tab ?? 'all', page],
    queryFn: () => orderApi.list({ status: tab ?? null, page, pageSize }),
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['orders'] });

  const cancel = useMutation({
    mutationFn: (orderId: number) => orderApi.cancel(orderId),
    onSuccess: () => {
      toast.success('订单已取消');
      refresh();
    },
  });

  const confirmReceipt = useMutation({
    mutationFn: (orderId: number) => orderApi.confirmReceipt(orderId),
    onSuccess: () => {
      toast.success('已确认收货');
      refresh();
    },
  });

  const onCancel = (orderId: number) => {
    if (window.confirm('确定取消该订单吗？')) cancel.mutate(orderId);
  };
  const onConfirm = (orderId: number) => {
    if (window.confirm('确认已收到货？')) confirmReceipt.mutate(orderId);
  };

  const orders = data?.list ?? [];
  const total = data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / pageSize));

  return (
    <div className="flex flex-col gap-4">
      <div className="flex gap-1 overflow-x-auto border-b border-gray-200 bg-white px-2">
        {ORDER_TABS.map((t) => (
          <button
            key={t.label}
            type="button"
            onClick={() => {
              setTab(t.status);
              setPage(1);
            }}
            className={`shrink-0 border-b-2 px-4 py-3 text-sm ${
              tab === t.status ? 'border-red-500 font-semibold text-red-600' : 'border-transparent text-gray-500'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {isLoading ? (
        <p className="py-20 text-center text-gray-400">加载中…</p>
      ) : orders.length === 0 ? (
        <div className="py-20 text-center text-gray-400">
          <p>暂无订单</p>
          <Link to="/" className="mt-2 inline-block text-sm text-red-500 hover:underline">
            去逛逛 →
          </Link>
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          {orders.map((o) => (
            <OrderCard
              key={o.orderId}
              order={o}
              onCancel={onCancel}
              onConfirm={onConfirm}
              onPay={() => navigate(`/pay/${o.orderId}`)}
            />
          ))}
          {pages > 1 && (
            <div className="flex items-center justify-center gap-4 text-sm">
              <button
                type="button"
                disabled={page <= 1}
                onClick={() => setPage((p) => p - 1)}
                className="rounded border border-gray-300 px-4 py-1.5 disabled:opacity-40"
              >
                上一页
              </button>
              <span className="text-gray-500">
                {page} / {pages}
              </span>
              <button
                type="button"
                disabled={page >= pages}
                onClick={() => setPage((p) => p + 1)}
                className="rounded border border-gray-300 px-4 py-1.5 disabled:opacity-40"
              >
                下一页
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function OrderCard({
  order,
  onCancel,
  onConfirm,
  onPay,
}: {
  order: OrderListVO;
  onCancel: (id: number) => void;
  onConfirm: (id: number) => void;
  onPay: () => void;
}) {
  const remain = useCountdown(order.payDeadline);
  return (
    <div className="rounded-lg border border-gray-200 bg-white">
      <div className="flex items-center gap-3 border-b border-gray-100 px-4 py-3 text-sm">
        <span className="text-gray-500">订单号 {order.orderNo}</span>
        <span className={`rounded px-2 py-0.5 text-xs ${statusTone(order.status)}`}>{order.statusText}</span>
        {isUnpaid(order.status) && remain > 0 && (
          <span className="text-xs text-orange-500">剩余 {formatCountdown(remain)} 自动关闭</span>
        )}
        <span className="ml-auto text-xs text-gray-400">{formatDateTime(order.createdAt)}</span>
      </div>
      {order.items.map((item) => (
        <Link
          key={`${item.skuId}-${item.orderId}`}
          to={`/orders/${order.orderId}`}
          className="flex items-center gap-4 border-b border-gray-100 px-4 py-3 hover:bg-gray-50"
        >
          <div className="h-16 w-16 shrink-0 overflow-hidden rounded bg-gray-100">
            <img src={item.skuImage} alt="" className="h-full w-full object-cover" />
          </div>
          <p className="line-clamp-2 flex-1 text-sm text-gray-700">{item.skuName}</p>
          <span className="w-20 text-right text-sm text-gray-600">{formatPrice(item.price)}</span>
          <span className="w-10 text-center text-sm text-gray-400">×{item.quantity}</span>
        </Link>
      ))}
      <div className="flex items-center gap-3 px-4 py-3">
        <span className="text-sm text-gray-500">
          共 {order.itemCount} 件 · 实付 <span className="font-semibold text-red-600">{formatPrice(order.payAmount)}</span>
        </span>
        <div className="ml-auto flex gap-2 text-xs">
          {isUnpaid(order.status) && (
            <>
              <button
                type="button"
                onClick={() => onCancel(order.orderId)}
                className="rounded border border-gray-300 px-4 py-1.5 text-gray-500 hover:border-gray-400"
              >
                取消订单
              </button>
              <button type="button" onClick={onPay} className="rounded bg-red-500 px-4 py-1.5 text-white hover:bg-red-600">
                去支付
              </button>
            </>
          )}
          {order.status === 2 && (
            <button
              type="button"
              onClick={() => onConfirm(order.orderId)}
              className="rounded bg-red-500 px-4 py-1.5 text-white hover:bg-red-600"
            >
              确认收货
            </button>
          )}
          <Link to={`/orders/${order.orderId}`} className="rounded border border-gray-300 px-4 py-1.5 text-gray-500 hover:border-gray-400">
            订单详情
          </Link>
        </div>
      </div>
    </div>
  );
}
