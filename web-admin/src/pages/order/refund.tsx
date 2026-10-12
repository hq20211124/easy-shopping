/**
 * 退款/售后处理（plans/admin-frontend.md 5.6；接口 admin-backend 4.2.2）
 *  - 售后单列表（状态 Tab：待审核/已同意/已拒绝/已完成）+ 同意/拒绝操作
 *  - 拒绝必须填写原因；退款原路返回，累计退款不超过订单实付（后端校验，5.6）
 *  - 退款完成后联动库存回补与积分/优惠券退回（后端 MQ 驱动）
 *  - ❌ 后端现状：/api/admin/after-sale/** 无对应 Controller（仅 mock 可用），详见 docs/api-contract-check.md
 *  - 权限码：后端权限点注册表只有 refund:list（无 refund:approve/reject），按钮统一用 refund:list
 */
import { useMemo, useState } from 'react';
import { Card, Form, Input, message, Modal, Space, Table, Tabs, Typography, type TableColumnsType } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { approveAfterSale, listAfterSales, rejectAfterSale, type AfterSaleItem } from '@/api/order';
import { AFTER_SALE_STATUS_OPTIONS } from '@/config/constants';
import { StatusTag } from '@/components/StatusTag';
import { AuthButton } from '@/components/AuthButton';
import { formatAmount } from '@/utils/format';
import { normalizePage } from '@/utils/query';

const TYPE_LABEL: Record<number, string> = { 1: '仅退款', 2: '退货退款', 3: '换货' };

export default function OrderRefund() {
  const queryClient = useQueryClient();
  const [statusTab, setStatusTab] = useState<string>('');
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [rejectTarget, setRejectTarget] = useState<AfterSaleItem | null>(null);
  const [rejectReason, setRejectReason] = useState('');

  const query = useMemo(() => ({ ...normalizePage(pageNum, pageSize), status: statusTab }), [pageNum, pageSize, statusTab]);

  const { data, isLoading } = useQuery({
    queryKey: ['after-sales', query],
    queryFn: () => listAfterSales({ ...query, status: statusTab === '' ? '' : Number(statusTab) }),
    placeholderData: (prev) => prev,
  });

  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['after-sales'] });

  const approveMutation = useMutation({
    mutationFn: (id: number) => approveAfterSale(id),
    onSuccess: () => {
      message.success('已同意退款，退款原路返回，库存将回补至原发货仓');
      invalidate();
    },
  });

  const rejectMutation = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) => rejectAfterSale(id, reason),
    onSuccess: () => {
      message.success('已拒绝退款，原因已同步买家');
      setRejectTarget(null);
      setRejectReason('');
      invalidate();
    },
  });

  const columns = useMemo(
    () =>
      [
        {
          title: '售后单号',
          key: 'no',
          width: 200,
          render: (_: unknown, row: AfterSaleItem) => (
            <div>
              {row.afterSaleNo}
              <div style={{ color: '#999', fontSize: 12 }}>{row.createdAt}</div>
            </div>
          ),
        },
        { title: '关联订单', dataIndex: 'orderNo', width: 210 },
        { title: '类型', dataIndex: 'type', width: 100, render: (v: number) => TYPE_LABEL[v] ?? '-' },
        { title: '退款金额(元)', dataIndex: 'amount', width: 120, render: (v: number) => formatAmount(v) },
        {
          title: '退款原因',
          key: 'reason',
          render: (_: unknown, row: AfterSaleItem) => (
            <div>
              <div>{row.reason}</div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {row.buyerNote}
              </Typography.Text>
            </div>
          ),
        },
        {
          title: '状态',
          dataIndex: 'status',
          width: 110,
          render: (v: number) => <StatusTag value={v} options={AFTER_SALE_STATUS_OPTIONS} />,
        },
        { title: '处理备注', dataIndex: 'handleNote', ellipsis: true },
        {
          title: '操作',
          key: 'action',
          fixed: 'right',
          width: 160,
          render: (_: unknown, row: AfterSaleItem) =>
            row.status === 0 ? (
              <Space size={4}>
                <AuthButton
                  permission="refund:list"
                  type="link"
                  size="small"
                  onClick={() => approveMutation.mutate(row.id)}
                >
                  同意退款
                </AuthButton>
                <AuthButton
                  permission="refund:list"
                  type="link"
                  size="small"
                  danger
                  onClick={() => {
                    setRejectTarget(row);
                    setRejectReason('');
                  }}
                >
                  拒绝
                </AuthButton>
              </Space>
            ) : null,
        },
      ] satisfies TableColumnsType<AfterSaleItem>,
    [approveMutation],
  );

  return (
    <Card className="page-card" title="退款售后">
      <Tabs
        activeKey={statusTab}
        onChange={(key) => {
          setStatusTab(key);
          setPageNum(1);
        }}
        items={[
          { key: '', label: '全部' },
          ...AFTER_SALE_STATUS_OPTIONS.map((option) => ({ key: String(option.value), label: option.label })),
        ]}
        style={{ marginBottom: 4 }}
      />

      <Table<AfterSaleItem>
        rowKey="id"
        size="middle"
        loading={isLoading}
        columns={columns}
        dataSource={data?.list}
        scroll={{ x: 1150 }}
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
        title={`拒绝退款 · ${rejectTarget?.afterSaleNo ?? ''}`}
        open={Boolean(rejectTarget)}
        confirmLoading={rejectMutation.isPending}
        onCancel={() => setRejectTarget(null)}
        onOk={() => {
          if (!rejectReason.trim()) {
            message.warning('拒绝退款必须填写原因');
            return;
          }
          if (rejectTarget) rejectMutation.mutate({ id: rejectTarget.id, reason: rejectReason.trim() });
        }}
      >
        <Form layout="vertical">
          <Form.Item label="拒绝原因（必填，将同步给买家）">
            <Input.TextArea
              rows={4}
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
              maxLength={200}
              showCount
            />
          </Form.Item>
        </Form>
        {rejectTarget && (
          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            退款金额：{formatAmount(rejectTarget.amount)} 元；累计退款不得超过订单实付（5.6，后端校验）。
          </Typography.Paragraph>
        )}
      </Modal>
    </Card>
  );
}
