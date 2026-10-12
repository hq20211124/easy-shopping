/**
 * 商品列表（SPU）（plans/admin-frontend.md 4.1；接口 admin-backend 3.2.1）
 *  - 查询表格 + 分页；批量上下架上限 500 条（core-product 同步执行并返回成功/失败明细）
 *  - 审核流状态展示：0-draft / 1-auditing / 2-on_shelf / 3-rejected（core-product AuditStatus，数字枚举）
 *  - 字段权限：无 cost:read 权限时成本价显示 ****（列表接口本就不下发成本价，详见 api 层注释）
 *  - 上下架经 MQ 同步买家端缓存/ES 为最终一致，提示"以买家端实际展示为准"（前端 1.7）
 *  - 后端未下发字段（库存/类目名/品牌名/成本价）显示 "-"，不编造数据
 */
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Button,
  Card,
  Form,
  Input,
  message,
  Popconfirm,
  Select,
  Space,
  Table,
  Typography,
  type TableColumnsType,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AUDIT,
  SHELF,
  batchShelf,
  listProducts,
  type AuditStatus,
  type ProductListItem,
  type ShelfStatus,
} from '@/api/product';
import { AUDIT_STATUS_OPTIONS, BATCH_MAX, SHELF_STATUS_OPTIONS } from '@/config/constants';
import { StatusTag } from '@/components/StatusTag';
import { AuthButton } from '@/components/AuthButton';
import { QueryErrorText } from '@/components/QueryError';
import { usePermission } from '@/hooks/usePermission';
import { formatAmount, formatNumber } from '@/utils/format';
import { normalizePage } from '@/utils/query';

interface Filters {
  keyword?: string;
  auditStatus?: AuditStatus;
  shelfStatus?: ShelfStatus;
}

export default function ProductList() {
  const queryClient = useQueryClient();
  const { hasPermission } = usePermission();
  const [form] = Form.useForm<Filters>();
  const [filters, setFilters] = useState<Filters>({});
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [selectedKeys, setSelectedKeys] = useState<number[]>([]);

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['products', filters, pageNum, pageSize],
    queryFn: () => listProducts({ ...normalizePage(pageNum, pageSize), ...filters }),
    placeholderData: (prev) => prev, // 翻页时保留上一页数据，避免表格闪烁
  });

  const shelfMutation = useMutation({
    mutationFn: ({ ids, shelf }: { ids: number[]; shelf: ShelfStatus }) => batchShelf(ids, shelf),
    onSuccess: (res, { shelf }) => {
      message.success(
        `已提交${shelf === SHELF.ON ? '上架' : '下架'}任务（成功 ${res.successCount} / 失败 ${res.failCount}），买家端缓存同步为最终一致，最长数分钟生效，以买家端实际展示为准`,
      );
      setSelectedKeys([]);
      void queryClient.invalidateQueries({ queryKey: ['products'] });
    },
  });

  const onBatchShelf = (shelf: ShelfStatus) => {
    if (selectedKeys.length === 0) {
      message.warning('请先勾选商品');
      return;
    }
    if (selectedKeys.length > BATCH_MAX) {
      message.warning(`批量操作单次上限 ${BATCH_MAX} 条，请分批提交`);
      return;
    }
    shelfMutation.mutate({ ids: selectedKeys, shelf });
  };

  const columns = useMemo(
    () =>
      [
        {
          title: '商品信息',
          key: 'product',
          width: 300,
          render: (_: unknown, row: ProductListItem) => (
            <div>
              <Link to={`/product/${row.id}`}>{row.title}</Link>
              <div style={{ color: '#999', fontSize: 12 }}>
                {row.spuCode}
                {row.categoryName ? ` · ${row.categoryName}` : ''}
                {row.brandName ? ` · ${row.brandName}` : ''}
              </div>
            </div>
          ),
        },
        {
          title: '销售价(元)',
          dataIndex: 'price',
          width: 110,
          render: (v: number) => formatAmount(v),
        },
        {
          title: '成本价(元)',
          key: 'costPrice',
          width: 110,
          // 字段权限：无 cost:read 时后端 costPrice 脱敏为 null；列表接口本就不下发成本价（缺口，显示 "-"）
          render: (_: unknown, row: ProductListItem) =>
            hasPermission('cost:read') ? formatAmount(row.costPrice) : '****',
        },
        { title: '库存', dataIndex: 'stock', width: 90, render: (v?: number) => formatNumber(v) },
        { title: '30日销量', dataIndex: 'sales30d', width: 100, render: (v: number) => formatNumber(v) },
        {
          title: '审核状态',
          dataIndex: 'auditStatus',
          width: 110,
          render: (v: number) => <StatusTag value={v} options={AUDIT_STATUS_OPTIONS} />,
        },
        {
          title: '上下架',
          dataIndex: 'shelfStatus',
          width: 100,
          render: (v: number) => <StatusTag value={v} options={SHELF_STATUS_OPTIONS} />,
        },
        { title: '更新时间', dataIndex: 'updatedAt', width: 170 },
        {
          title: '操作',
          key: 'action',
          fixed: 'right',
          width: 160,
          render: (_: unknown, row: ProductListItem) => (
            <Space size={4}>
              <Link to={`/product/${row.id}`}>详情</Link>
              {row.shelfStatus === SHELF.OFF ? (
                <Popconfirm
                  title="确认上架该商品？"
                  description="上架前置条件为审核通过（auditStatus=2）；上架后将通过 MQ 同步买家端缓存与 ES 索引。"
                  onConfirm={() => {
                    if (row.auditStatus !== AUDIT.ON_SHELF) {
                      message.warning('商品未通过审核，不能上架');
                      return;
                    }
                    shelfMutation.mutate({ ids: [row.id], shelf: SHELF.ON });
                  }}
                >
                  <AuthButton permission="product:edit" type="link" size="small">
                    上架
                  </AuthButton>
                </Popconfirm>
              ) : (
                <Popconfirm
                  title="确认下架该商品？"
                  onConfirm={() => shelfMutation.mutate({ ids: [row.id], shelf: SHELF.OFF })}
                >
                  <AuthButton permission="product:edit" type="link" size="small" danger>
                    下架
                  </AuthButton>
                </Popconfirm>
              )}
            </Space>
          ),
        },
      ] satisfies TableColumnsType<ProductListItem>,
    [hasPermission, shelfMutation],
  );

  return (
    <Card className="page-card" title="商品列表">
      <Form
        form={form}
        layout="inline"
        style={{ marginBottom: 16 }}
        onFinish={(values: Filters) => {
          setFilters(values);
          setPageNum(1);
        }}
      >
        <Form.Item name="keyword" label="关键词">
          <Input placeholder="商品名称 / SPU编码" allowClear style={{ width: 220 }} />
        </Form.Item>
        <Form.Item name="auditStatus" label="审核状态">
          <Select placeholder="全部" allowClear style={{ width: 140 }} options={[...AUDIT_STATUS_OPTIONS]} />
        </Form.Item>
        <Form.Item name="shelfStatus" label="上下架">
          <Select placeholder="全部" allowClear style={{ width: 120 }} options={[...SHELF_STATUS_OPTIONS]} />
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

      <Space style={{ marginBottom: 12 }}>
        <AuthButton permission="product:edit" disabled={shelfMutation.isPending} onClick={() => onBatchShelf(SHELF.ON)}>
          批量上架
        </AuthButton>
        <AuthButton
          permission="product:edit"
          disabled={shelfMutation.isPending}
          onClick={() => onBatchShelf(SHELF.OFF)}
        >
          批量下架
        </AuthButton>
        <Typography.Text type="secondary">
          已选 {selectedKeys.length} 条（单次上限 {BATCH_MAX} 条，超出请分批）
        </Typography.Text>
      </Space>

      <Table<ProductListItem>
        rowKey="id"
        size="middle"
        loading={isLoading}
        columns={columns}
        dataSource={data?.list}
        // 「加载失败」≠「没有商品」：显式错误文案 + 重试入口
        locale={{ emptyText: isError ? <QueryErrorText error={error} onRetry={() => void refetch()} /> : '暂无商品' }}
        scroll={{ x: 1300 }}
        rowSelection={{
          selectedRowKeys: selectedKeys,
          onChange: (keys) => setSelectedKeys(keys as number[]),
        }}
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
    </Card>
  );
}
