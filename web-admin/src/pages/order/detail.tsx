/**
 * 订单详情（plans/admin-frontend.md 5.2；接口 admin-backend 4.2.1 → core-order AdminOrderController）
 *  - 状态时间轴：下单 → 付款 → 发货 → 完成；非法流转按钮直接隐藏（5.3 状态机白名单）
 *  - 金额明细：商品总额 + 运费 - 优惠 = 实付（金额单位：分，API 层已把后端「元」换算为「分」）
 *  - 订单备注（内部可见）与关闭订单写入操作日志（2.4 / 5.2 审计边界）
 *  - 后端缺口降级：AdminOrderVO 未下发 payDeadline/receiverAddress/shippedAt/adminRemarks → 页面显示 "-"
 */
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Input,
  List,
  message,
  Modal,
  Popconfirm,
  Space,
  Steps,
  Tag,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ORDER_REMARK_AVAILABLE, addOrderRemark, closeOrder, getOrderDetail } from '@/api/order';
import { ORDER_STATUS_OPTIONS, PAY_TYPE_OPTIONS } from '@/config/constants';
import { StatusTag } from '@/components/StatusTag';
import { AuthButton } from '@/components/AuthButton';
import { PageLoading } from '@/components/PageLoading';
import { formatAmount, maskPhone } from '@/utils/format';

export default function OrderDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [remarkOpen, setRemarkOpen] = useState(false);
  const [remarkText, setRemarkText] = useState('');

  const { data: order, isLoading } = useQuery({
    queryKey: ['order', id],
    queryFn: () => getOrderDetail(Number(id)),
    enabled: Boolean(id),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['order', id] });
    void queryClient.invalidateQueries({ queryKey: ['orders'] });
  };

  const remarkMutation = useMutation({
    mutationFn: (content: string) => addOrderRemark(Number(id), content),
    onSuccess: () => {
      message.success('备注已保存');
      setRemarkOpen(false);
      setRemarkText('');
      invalidate();
    },
  });

  const closeMutation = useMutation({
    mutationFn: (reason: string) => closeOrder(Number(id), reason),
    onSuccess: () => {
      message.success('订单已关闭；锁定库存将联动释放');
      invalidate();
    },
  });

  if (isLoading || !order) return <PageLoading />;

  // 状态步骤映射（admin-backend 4.3 状态机：0待付款→1待发货→2待收货→3已完成）
  const stepIndex = [0, 1, 2, 3, 3, 3, 0, 0][order.orderStatus];
  const terminal = order.orderStatus === 6 || order.orderStatus === 7;

  return (
    <Card
      className="page-card"
      title={`订单详情 · ${order.orderNo}`}
      extra={
        <Space>
          <Button onClick={() => navigate(-1)}>返回</Button>
          {ORDER_REMARK_AVAILABLE && (
            <AuthButton permission="order:detail" onClick={() => setRemarkOpen(true)}>
              添加备注
            </AuthButton>
          )}
          {[0, 1].includes(order.orderStatus) && (
            <Popconfirm
              title="确认关闭订单？"
              description="关闭后不可恢复；已支付订单请先走售后流程。"
              onConfirm={() => closeMutation.mutate('管理员手动关闭')}
            >
              <AuthButton permission="order:close" danger>
                关闭订单
              </AuthButton>
            </Popconfirm>
          )}
        </Space>
      }
    >
      <Steps
        size="small"
        current={stepIndex}
        status={terminal ? 'error' : undefined}
        items={[{ title: '已下单' }, { title: '已付款' }, { title: '已发货' }, { title: '已完成' }]}
        style={{ marginBottom: 24, maxWidth: 760 }}
      />

      {order.orderStatus === 4 && (
        <Alert type="warning" showIcon style={{ marginBottom: 16 }} message="订单退款中，请前往「退款售后」处理退款单" />
      )}
      {order.orderStatus === 6 && (
        <Alert type="info" showIcon style={{ marginBottom: 16 }} message="订单已取消（未支付即取消，无资金流）" />
      )}
      {order.orderStatus === 7 && (
        <Alert type="info" showIcon style={{ marginBottom: 16 }} message="订单已关闭（售后完成或后台关闭，存在资金处置记录）" />
      )}

      <Card type="inner" title="订单信息" style={{ marginBottom: 16 }}>
        <Descriptions size="small" column={3}>
          <Descriptions.Item label="订单号">{order.orderNo}</Descriptions.Item>
          <Descriptions.Item label="状态">
            <StatusTag value={order.orderStatus} options={ORDER_STATUS_OPTIONS} />
          </Descriptions.Item>
          <Descriptions.Item label="店铺">{order.storeName || '-'}</Descriptions.Item>
          <Descriptions.Item label="下单时间">{order.createdAt}</Descriptions.Item>
          <Descriptions.Item label="支付时间">{order.paidAt ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="支付方式">
            {PAY_TYPE_OPTIONS.find((p) => p.value === order.payType)?.label ?? '-'}
          </Descriptions.Item>
          {order.orderStatus === 0 && (
            <Descriptions.Item label="支付截止" span={3}>
              <Tag color="warning">{order.payDeadline || '-'}</Tag>
              <span style={{ color: '#999', fontSize: 12 }}>
                以服务端下发的 pay_deadline 为准（前端不自定义超时分钟数）；当前后端 AdminOrderVO 未下发该字段，联调后自动显示
              </span>
            </Descriptions.Item>
          )}
        </Descriptions>
      </Card>

      <Card type="inner" title="金额明细" style={{ marginBottom: 16 }}>
        <Descriptions size="small" column={4}>
          <Descriptions.Item label="商品总额">{formatAmount(order.totalAmount)} 元</Descriptions.Item>
          <Descriptions.Item label="运费">{formatAmount(order.freight)} 元</Descriptions.Item>
          <Descriptions.Item label="优惠">- {formatAmount(order.discount)} 元</Descriptions.Item>
          <Descriptions.Item label="实付">
            <strong>{formatAmount(order.payAmount)} 元</strong>
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card type="inner" title="收货与买家" style={{ marginBottom: 16 }}>
        <Descriptions size="small" column={3}>
          <Descriptions.Item label="收货人">{order.receiver.name || '-'}</Descriptions.Item>
          <Descriptions.Item label="收货手机号">{maskPhone(order.receiver.phone)}</Descriptions.Item>
          <Descriptions.Item label="买家">{order.buyerName || '-'}</Descriptions.Item>
          <Descriptions.Item label="收货地址" span={3}>
            {order.receiver.address || '后端 AdminOrderVO 未下发 receiverAddress（缺口，联调后自动显示）'}
            <span style={{ color: '#999', fontSize: 12, marginLeft: 8 }}>
              敏感字段以后端脱敏下发为主（前端 2.2 / 18.6）
            </span>
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card type="inner" title={`内部备注（${order.adminRemarks.length}）`}>
        <List
          dataSource={order.adminRemarks}
          locale={{ emptyText: '暂无备注（后端未实现订单备注接口，当前仅 mock 环境可写入）' }}
          renderItem={(item) => (
            <List.Item>
              <List.Item.Meta
                title={`${item.operator} · ${item.time}`}
                description={item.content}
              />
            </List.Item>
          )}
        />
      </Card>

      <Modal
        title="添加备注"
        open={remarkOpen}
        confirmLoading={remarkMutation.isPending}
        onCancel={() => setRemarkOpen(false)}
        onOk={() => {
          if (!remarkText.trim()) {
            message.warning('请输入备注内容');
            return;
          }
          remarkMutation.mutate(remarkText.trim());
        }}
      >
        <Input.TextArea
          rows={4}
          value={remarkText}
          onChange={(e) => setRemarkText(e.target.value)}
          placeholder="内部备注，买家不可见"
          maxLength={200}
          showCount
        />
      </Modal>
    </Card>
  );
}
