/**
 * 商品详情（plans/admin-frontend.md 4.2 / 4.3；接口 admin-backend 3.2.1、3.3.2）
 *  - 基本信息 + SKU 明细 + 审核流（core-product AuditStatus：0-draft 1-auditing 2-on_shelf 3-rejected）
 *  - 编辑表单：销售价以「分」传输，输入框按「元」展示（9.6 金额精度规范）
 *  - 后端约束（core-product ProductService.update）：仅 draft/rejected 可编辑；SKU 现价以 core-price 为准
 *  - 后端未下发字段（类目名/品牌名/库存/审核记录）显示 "-"，不编造数据
 */
import { useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Space,
  Steps,
  Table,
  Tag,
  Timeline,
  Typography,
  message,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AUDIT, SHELF, getProductDetail, updateProduct } from '@/api/product';
import { AUDIT_STATUS_OPTIONS } from '@/config/constants';
import { StatusTag } from '@/components/StatusTag';
import { AuthButton } from '@/components/AuthButton';
import { PageLoading } from '@/components/PageLoading';
import { usePermission } from '@/hooks/usePermission';
import { formatAmount } from '@/utils/format';

/** 审核流步骤：0草稿/退回 → 1审核中 → 2已上架（rejected 归到"待提交/退回修改"） */
const AUDIT_STEP_INDEX: Record<number, number> = { [AUDIT.DRAFT]: 0, [AUDIT.REJECTED]: 0, [AUDIT.AUDITING]: 1, [AUDIT.ON_SHELF]: 2 };

export default function ProductDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const [form] = Form.useForm<{ title: string; subtitle: string; price: number }>();

  const { data: detail, isLoading } = useQuery({
    queryKey: ['product', id],
    queryFn: () => getProductDetail(Number(id)),
    enabled: Boolean(id),
  });

  useEffect(() => {
    if (detail) {
      form.setFieldsValue({
        title: detail.title,
        subtitle: detail.subtitle,
        price: Number((detail.price / 100).toFixed(2)),
      });
    }
  }, [detail, form]);

  const updateMutation = useMutation({
    mutationFn: (values: { title: string; subtitle: string; price: number }) =>
      updateProduct(Number(id), { ...values, price: Math.round(values.price * 100) }),
    onSuccess: () => {
      message.success('保存成功；商品变更经 MQ 同步买家端缓存，最长数分钟生效，以买家端实际展示为准');
      void queryClient.invalidateQueries({ queryKey: ['product', id] });
      void queryClient.invalidateQueries({ queryKey: ['products'] });
    },
  });

  if (isLoading || !detail) return <PageLoading />;

  // 后端约束：仅 draft(0)/rejected(3) 可编辑（core-product ProductService.update）
  const editable = detail.auditStatus === AUDIT.DRAFT || detail.auditStatus === AUDIT.REJECTED;

  return (
    <div>
      <Card
        className="page-card"
        title={`商品详情 · ${detail.spuCode}`}
        extra={
          <Space>
            <Button onClick={() => navigate(-1)}>返回</Button>
          </Space>
        }
      >
        <Steps
          size="small"
          current={AUDIT_STEP_INDEX[detail.auditStatus] ?? 0}
          status={detail.auditStatus === AUDIT.REJECTED ? 'error' : undefined}
          items={[{ title: '待提交 / 退回修改' }, { title: '审核中' }, { title: '已上架' }]}
          style={{ marginBottom: 24, maxWidth: 720 }}
        />

        {detail.auditStatus === AUDIT.REJECTED && detail.auditRemark && (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 16 }}
            message="审核被驳回"
            description={`原因：${detail.auditRemark}。修改后可重新提交审核。`}
          />
        )}

        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label="商品标题">{detail.title}</Descriptions.Item>
          <Descriptions.Item label="副标题">{detail.subtitle || '-'}</Descriptions.Item>
          <Descriptions.Item label="类目">{detail.categoryName ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="品牌">{detail.brandName ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="销售价">{formatAmount(detail.price)} 元</Descriptions.Item>
          <Descriptions.Item label="划线价">{formatAmount(detail.linePrice)} 元</Descriptions.Item>
          <Descriptions.Item label="成本价">
            {hasPermission('cost:read') ? `${formatAmount(detail.costPrice)} 元` : '****'}
          </Descriptions.Item>
          <Descriptions.Item label="总库存">{detail.stock ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="审核状态">
            <StatusTag value={detail.auditStatus} options={AUDIT_STATUS_OPTIONS} />
          </Descriptions.Item>
          <Descriptions.Item label="上下架">
            <Tag color={detail.shelfStatus === SHELF.ON ? 'success' : 'default'}>
              {detail.shelfStatus === SHELF.ON ? '已上架' : '已下架'}
            </Tag>
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card className="page-card" title="SKU 明细">
        <Table
          rowKey="skuCode"
          size="small"
          pagination={false}
          dataSource={detail.skus}
          locale={{ emptyText: '该商品暂无 SKU' }}
          columns={[
            { title: 'SKU 编码', dataIndex: 'skuCode' },
            { title: '规格', dataIndex: 'spec', render: (v: string) => v || '-' },
            { title: '价格(元)', dataIndex: 'price', render: (v: number) => formatAmount(v) },
            { title: '库存', dataIndex: 'stock', render: (v?: number) => v ?? '-' },
            {
              title: '状态',
              dataIndex: 'enabled',
              render: (v: boolean) => <Tag color={v ? 'success' : 'default'}>{v ? '启用' : '停用'}</Tag>,
            },
          ]}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          SKU 现价来自 core-price（/api/v1/price/skus）；库存归 core-inventory，当前接口未下发（缺口，见 api-contract-check）。
        </Typography.Text>
      </Card>

      <Card className="page-card" title="审核记录">
        {detail.auditRecords.length === 0 ? (
          <Typography.Text type="secondary">
            暂无审核记录（core-product 未实现审核记录接口，缺口见 api-contract-check.md）。
          </Typography.Text>
        ) : (
          <Timeline
            items={detail.auditRecords.map((record) => ({
              color: record.action === '驳回' ? 'red' : record.action === '审核通过' ? 'green' : 'blue',
              children: (
                <div>
                  <div>
                    <Tag>{record.action}</Tag>
                    <strong>{record.operator}</strong>
                    <span style={{ marginLeft: 8, color: '#999' }}>{record.time}</span>
                  </div>
                  {record.opinion && <div style={{ marginTop: 4 }}>意见：{record.opinion}</div>}
                </div>
              ),
            }))}
          />
        )}
      </Card>

      <Card className="page-card" title="编辑基本信息">
        {!editable && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="当前状态不可编辑"
            description="后端仅允许草稿（待提交）/ 审核拒绝状态编辑商品（core-product 3.3.2 状态机）。"
          />
        )}
        <Form
          form={form}
          layout="vertical"
          style={{ maxWidth: 560 }}
          onFinish={(values) => updateMutation.mutate(values)}
        >
          <Form.Item name="title" label="商品标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input maxLength={60} showCount disabled={!editable} />
          </Form.Item>
          <Form.Item name="subtitle" label="副标题">
            <Input maxLength={120} showCount disabled={!editable} />
          </Form.Item>
          <Form.Item
            name="price"
            label="销售价（元）"
            rules={[{ required: true, message: '请输入销售价' }]}
            extra="金额以「分」为整数传输，前端禁止浮点运算金额（9.6）；多 SKU 商品按商品级改价下发到全部 SKU"
          >
            <InputNumber min={0.01} precision={2} style={{ width: 240 }} disabled={!editable} />
          </Form.Item>
          <Form.Item>
            <AuthButton
              permission="product:edit"
              type="primary"
              htmlType="submit"
              disabled={!editable}
              loading={updateMutation.isPending}
            >
              保存
            </AuthButton>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}
