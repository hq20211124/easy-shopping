/** 支付成功页：以订单状态为准（≥1 已支付）。 */

import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { orderApi } from '@/api/order';
import { formatPrice } from '@/utils/format';

export function PayResultPage() {
  const { orderId } = useParams();
  const id = Number(orderId);
  const { data: order } = useQuery({
    queryKey: ['order', id],
    queryFn: () => orderApi.detail(id),
    enabled: Number.isFinite(id),
  });

  const paid = !!order && order.status >= 1;

  return (
    <div className="mx-auto max-w-xl py-16 text-center">
      <div className={`mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-full text-4xl ${paid ? 'bg-green-100 text-green-600' : 'bg-orange-100 text-orange-500'}`}>
        {paid ? '✓' : '…'}
      </div>
      <h1 className={`text-2xl font-bold ${paid ? 'text-green-600' : 'text-orange-500'}`}>
        {paid ? '支付成功' : '支付处理中'}
      </h1>
      {order && (
        <p className="mt-3 text-sm text-gray-500">
          订单号 {order.orderNo} · 实付 <span className="text-gray-800">{formatPrice(order.amountDetail.payAmount)}</span>
        </p>
      )}
      <div className="mt-8 flex justify-center gap-4">
        <Link
          to={`/orders/${id}`}
          className="h-10 rounded-full border border-red-500 px-8 leading-10 text-sm text-red-600 hover:bg-red-50"
        >
          查看订单
        </Link>
        <Link
          to="/orders"
          className="h-10 rounded-full bg-red-500 px-8 leading-10 text-sm text-white hover:bg-red-600"
        >
          我的订单
        </Link>
      </div>
    </div>
  );
}
