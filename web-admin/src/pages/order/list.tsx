/**
 * 订单列表（plans/admin-frontend.md 5.1；接口 admin-backend 4.2.1 → core-order AdminOrderController）
 *  - 8 值状态 Tab（core-order OrderStatus：0待付款…7已关闭，全系统唯一定义，禁止自造状态）
 *  - 查询表格 + 分页；金额以「分」传输、展示走 formatAmount（9.6），API 层已把后端「元」换算为「分」
 *  - 查询参数对齐后端：订单号**精确匹配**、买家按**用户ID**查询（后端无昵称模糊查询）
 *  - 敏感操作按状态白名单渲染：关闭订单仅待付款/待发货可操作（5.3 状态不可逆）
 */
import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Button, Card, Form, Input, message, Modal, Popconfirm, Space, Table, Tabs, Typography, type TableColumnsType } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ORDER_REMARK_AVAILABLE, addOrderRemark, closeOrder, listOrders, type OrderListItem, type OrderStatus } from '@/api/order';
import { ORDER_STATUS_OPTIONS, PAY_TYPE_OPTIONS } from '@/config/constants';
import { StatusTag } from '@/components/StatusTag';
import { AuthButton } from '@/components/AuthButton';
import { QueryErrorText } from '@/components/QueryError';
import { formatAmount, maskPhone } from '@/utils/format';
import { normalizePage } from '@/utils/query';

export default function OrderList() {
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const [form] = Form.useForm<{ orderNo?: string; buyer?: string }>();
  const [filters, setFilters] = useState<{ orderNo?: string; buyer?: string }>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [remarkTarget, setRemarkTarget] = useState<OrderListItem | null>(null);
  const [remarkText, setRemarkText] = useState('');

  const statusParam = searchParams.get('orderStatus') ?? '';
  const orderStatus: OrderStatus | '' = statusParam === '' ? '' : (Number(statusParam) as OrderStatus);

  const query = useMemo(
    () => ({ ...normalizePage(pageNum, pageSize), ...filters, orderStatus }),
    [pageNum, pageSize, filters, orderStatus],
  );

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['orders', query],
    queryFn: () => listOrders(query),
    placeholderData: (prev) => prev,
  });

  const remarkMutation = useMutation({
    mutationFn: ({ id, content }: { id: number; content: string }) => addOrderRemark(id, content),
    onSuccess: () => {
      message.success('备注已保存');
      setRemarkTarget(null);
      setRemarkText('');
      void queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
  });

  const closeMutation = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) => closeOrder(id, reason),
    onSuccess: () => {
      message.success('订单已关闭；锁定库存将联动释放（4.4.4）');
      void queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
  });

  const columns = useMemo(
    () =>
      [
        {
          title: '订单信息',
          key: 'order',
          width: 220,
          render: (_: unknown, row: OrderListItem) => (
            <div>
              <Link to={`/order/${row.id}`}>{row.orderNo}</Link>
              <div style={{ color: '#999', fontSize: 12 }}>{row.createdAt}</div>
            </div>
          ),
        },
        {
          title: '商品',
          key: 'product',
          width: 240,
          render: (_: unknown, row: OrderListItem) => (
            <Typography.Text ellipsis style={{ maxWidth: 220 }} title={row.productTitle}>
              {row.productTitle || '-'} × {row.itemCount}
            </Typography.Text>
          ),
        },
        {
          title: '收货人',
          key: 'buyer',
          width: 160,
          render: (_: unknown, row: OrderListItem) => (
            <div>
              {row.buyerName || '-'}
              <div style={{ color: '#999', fontSize: 12 }}>{maskPhone(row.buyerPhone)}</div>
            </div>
          ),
        },
        {
          title: '实付(元)',
          dataIndex: 'payAmount',
          width: 120,
          render: (v: number) => formatAmount(v),
        },
        {
          title: '支付方式',
          dataIndex: 'payType',
          width: 110,
          render: (v: number) => PAY_TYPE_OPTIONS.find((p) => p.value === v)?.label ?? '-',
        },
        {
          title: '状态',
          dataIndex: 'orderStatus',
          width: 110,
          render: (v: number) => <StatusTag value={v} options={ORDER_STATUS_OPTIONS} />,
        },
        {
          title: '操作',
          key: 'action',
          fixed: 'right',
          width: 170,
          render: (_: unknown, row: OrderListItem) => (
            <Space size={4}>
              <Link to={`/order/${row.id}`}>详情</Link>
              {ORDER_REMARK_AVAILABLE && (
                <AuthButton
                  permission="order:detail"
                  type="link"
                  size="small"
                  onClick={() => {
                    setRemarkTarget(row);
                    setRemarkText('');
                  }}
                >
                  备注
                </AuthButton>
              )}
              {[0, 1].includes(row.orderStatus) && (
                <Popconfirm
                  title="确认关闭订单？"
                  description="关闭后不可恢复，将联动释放锁定库存。"
                  onConfirm={() => closeMutation.mutate({ id: row.id, reason: '管理员手动关闭' })}
                >
                  <AuthButton permission="order:close" type="link" size="small" danger>
                    关闭
                  </AuthButton>
                </Popconfirm>
              )}
            </Space>
          ),
        },
      ] satisfies TableColumnsType<OrderListItem>,
    [closeMutation],
  );

  return (
    <Card className="page-card" title="订单列表">
      <Form
        form={form}
        layout="inline"
        style={{ marginBottom: 16 }}
        onFinish={(values) => {
          setFilters(values);
          setPageNum(1);
        }}
      >
        <Form.Item name="orderNo" label="订单号">
          <Input placeholder="订单号精确匹配" allowClear style={{ width: 220 }} />
        </Form.Item>
        <Form.Item name="buyer" label="用户ID">
          <Input placeholder="买家用户ID" allowClear style={{ width: 160 }} />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" htmlType="submit">
              查询
            </Button>
            <Button
              onClick={() => {
                form.resetFields();
                setFilters({});
                setPageNum(1);
              }}
            >
              重置
            </Button>
          </Space>
        </Form.Item>
      </Form>

      <Tabs
        activeKey={statusParam}
        onChange={(key) => {
          const next = new URLSearchParams(searchParams);
          if (key) next.set('orderStatus', key);
          else next.delete('orderStatus');
          setSearchParams(next, { replace: true });
          setPageNum(1);
        }}
        items={[
          { key: '', label: '全部' },
          ...ORDER_STATUS_OPTIONS.map((option) => ({
            key: String(option.value),
            label: option.label,
          })),
        ]}
        style={{ marginBottom: 4 }}
      />

      <Table<OrderListItem>
        rowKey="id"
        size="middle"
        loading={isLoading}
        columns={columns}
        dataSource={data?.list}
        // 「加载失败」≠「没有订单」：显式错误文案 + 重试入口
        locale={{ emptyText: isError ? <QueryErrorText error={error} onRetry={() => void refetch()} /> : '暂无订单' }}
        scroll={{ x: 1200 }}
        pagination={{
          current: data?.pageNum ?? pageNum,
          pageSize: data?.pageSize ?? pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          showTotal: (total) => `共 ${total} 条`,
          onChange: (p, ps) => {
            setPageNum(p);
            setPageSize(ps);
          },
        }}
      />

      <Modal
        title={`订单备注 · ${remarkTarget?.orderNo ?? ''}`}
        open={Boolean(remarkTarget)}
        confirmLoading={remarkMutation.isPending}
        onCancel={() => setRemarkTarget(null)}
        onOk={() => {
          if (!remarkText.trim()) {
            message.warning('请输入备注内容');
            return;
          }
          if (remarkTarget) remarkMutation.mutate({ id: remarkTarget.id, content: remarkText.trim() });
        }}
      >
        <Input.TextArea
          rows={4}
          value={remarkText}
          onChange={(e) => setRemarkText(e.target.value)}
          placeholder="内部备注，买家不可见；敏感操作会写入操作日志"
          maxLength={200}
          showCount
        />
      </Modal>
    </Card>
  );
}
