/** 支付页：prepay（服务端定额，防改价）→ mock 付款 → 轮询结果 → 成功页。 */

import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { orderApi } from '@/api/order';
import { paymentApi } from '@/api/payment';
import { useCountdown } from '@/hooks/useCountdown';
import { toast } from '@/store/toast';
import { formatCountdown, formatPrice } from '@/utils/format';
import { isUnpaid } from '@/utils/orderStatus';

const CHANNELS = [
  { value: 1, label: '支付宝' },
  { value: 2, label: '微信支付' },
];

export function PayPage() {
  const { orderId } = useParams();
  const navigate = useNavigate();
  const [channel, setChannel] = useState(1);
  const id = Number(orderId);

  const { data: order } = useQuery({
    queryKey: ['order', id],
    queryFn: () => orderApi.detail(id),
    enabled: Number.isFinite(id),
  });

  // 倒计时读接口 pay_deadline 字段（裁决 C7：不硬编码支付超时分钟数）
  const remain = useCountdown(order?.payDeadline);

  const pay = useMutation({
    mutationFn: async () => {
      const prepay = await paymentApi.prepay(id, channel); // 金额由服务端反查订单
      await paymentApi.mockPay(prepay.paymentId); // P0 mock 付款，走真实回调链
      // 轮询确认入账（真实渠道由回调驱动，前端轮询兜底）
      for (let i = 0; i < 10; i++) {
        const status = await paymentApi.status(id);
        if (status.status === 'paid') return;
        await new Promise((r) => setTimeout(r, 500));
      }
      throw new Error('支付结果确认超时');
    },
    onSuccess: () => navigate(`/pay-result/${id}`, { replace: true }),
  });

  if (!order) return <p className="py-20 text-center text-gray-400">加载中…</p>;

  return (
    <div className="mx-auto max-w-xl">
      <h1 className="mb-4 text-lg font-semibold text-gray-800">收银台</h1>
      <div className="rounded-lg border border-gray-200 bg-white p-6">
        <div className="border-b border-dashed border-gray-200 pb-4 text-center">
          <p className="text-sm text-gray-500">订单号 {order.orderNo}</p>
          <p className="mt-2 text-3xl font-bold text-red-600">{formatPrice(order.amountDetail.payAmount)}</p>
          {isUnpaid(order.status) && remain > 0 && (
            <p className="mt-2 text-sm text-orange-500">支付剩余 {formatCountdown(remain)}</p>
          )}
        </div>

        {!isUnpaid(order.status) ? (
          <div className="pt-6 text-center">
            <p className="text-green-600">该订单已支付</p>
            <Link to={`/orders/${id}`} className="mt-3 inline-block text-sm text-red-500 hover:underline">
              查看订单详情 →
            </Link>
          </div>
        ) : (
          <>
            <div className="flex flex-col gap-3 py-5">
              {CHANNELS.map((c) => (
                <label
                  key={c.value}
                  className={`flex cursor-pointer items-center gap-3 rounded border p-3 text-sm ${
                    channel === c.value ? 'border-red-500 bg-red-50' : 'border-gray-200'
                  }`}
                >
                  <input type="radio" name="channel" checked={channel === c.value} onChange={() => setChannel(c.value)} />
                  {c.label}
                </label>
              ))}
            </div>
            <button
              type="button"
              disabled={pay.isPending}
              onClick={() => pay.mutate()}
              className="h-11 w-full rounded-full bg-red-500 text-sm font-semibold text-white hover:bg-red-600 disabled:bg-red-200"
            >
              {pay.isPending ? '支付处理中…' : `确认支付 ${formatPrice(order.amountDetail.payAmount)}`}
            </button>
            <p className="mt-3 text-center text-xs text-gray-400">P0 演示环境：点击后模拟支付成功并回调入账</p>
          </>
        )}
      </div>
      <p className="mt-4 text-center">
        <button type="button" onClick={() => toast.info('支付已取消')} className="text-xs text-gray-400 hover:text-gray-600">
          支付遇到问题？
        </button>
      </p>
    </div>
  );
}
