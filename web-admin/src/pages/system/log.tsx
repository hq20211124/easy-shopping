/**
 * 操作日志（plans/admin-frontend.md 12.2；接口 admin-backend 2.5.2 / 2.4）
 *  - 列表：操作人 / 模块 / 时间范围（上限 90 天，16.4 查询边界）筛选 + 分页
 *  - 详情抽屉：操作前后数据对比（改价、上下架、权限变更等敏感操作留痕）
 */
import { useMemo, useState } from 'react';
import { Card, Col, DatePicker, Descriptions, Drawer, Input, Row, Select, Space, Table, Tag, type TableColumnsType } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { listOperationLogs, type OperationLog } from '@/api/system';
import { QueryErrorText } from '@/components/QueryError';
import { assertTimeRange, normalizePage } from '@/utils/query';
import type { Dayjs } from 'dayjs';

const MODULE_OPTIONS = ['商品管理', '订单管理', '退款售后', '用户管理', '系统设置'].map((m) => ({ label: m, value: m }));

export default function SystemLog() {
  const [operator, setOperator] = useState('');
  const [module, setModule] = useState<string>('');
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [detail, setDetail] = useState<OperationLog | null>(null);

  const pageQuery = useMemo(() => {
    const { pageNum: p, pageSize: s } = normalizePage(pageNum, pageSize);
    return {
      pageNum: p,
      pageSize: s,
      operator: operator || undefined,
      module: module || undefined,
      createdAtStart: range?.[0]?.format('YYYY-MM-DD 00:00:00'),
      createdAtEnd: range?.[1]?.format('YYYY-MM-DD 23:59:59'),
    };
  }, [pageNum, pageSize, operator, module, range]);

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['operation-logs', pageQuery],
    queryFn: () => listOperationLogs(pageQuery),
    placeholderData: (prev) => prev,
  });

  const columns: TableColumnsType<OperationLog> = [
    { title: '操作人', dataIndex: 'operator', width: 120 },
    { title: '模块', dataIndex: 'module', width: 110 },
    { title: '操作', dataIndex: 'action', width: 130 },
    { title: '内容', dataIndex: 'content', ellipsis: true },
    { title: 'IP', dataIndex: 'ip', width: 140 },
    {
      title: '结果',
      dataIndex: 'status',
      width: 90,
      render: (v: number) => <Tag color={v === 1 ? 'success' : 'error'}>{v === 1 ? '成功' : '失败'}</Tag>,
    },
    { title: '耗时', dataIndex: 'duration', width: 90, render: (v: number) => `${v}ms` },
    { title: '时间', dataIndex: 'createdAt', width: 170 },
    {
      title: '操作',
      key: 'action',
      fixed: 'right',
      width: 90,
      render: (_, row: OperationLog) => (
        <a onClick={() => setDetail(row)}>详情</a>
      ),
    },
  ];

  return (
    <>
      <Card className="page-card" title="操作日志">
        <Space style={{ marginBottom: 16 }} wrap>
          <Input.Search
            placeholder="操作人"
            allowClear
            style={{ width: 180 }}
            onSearch={(value) => {
              setOperator(value.trim());
              setPageNum(1);
            }}
          />
          <Select
            placeholder="模块"
            allowClear
            style={{ width: 140 }}
            options={MODULE_OPTIONS}
            onChange={(value) => {
              setModule(value ?? '');
              setPageNum(1);
            }}
          />
          <DatePicker.RangePicker
            allowClear
            onChange={(dates) => {
              if (
                dates?.[0] &&
                dates?.[1] &&
                !assertTimeRange(dates[0].startOf('day').toISOString(), dates[1].endOf('day').toISOString())
              ) {
                return;
              }
              setRange(dates);
              setPageNum(1);
            }}
          />
        </Space>
        <div style={{ marginBottom: 12, color: '#8c8c8c', fontSize: 12 }}>
          关键字（操作人 / 模块）与时间范围互斥：填写关键字时忽略时间范围（后端 P0 限制，关键字优先）。
        </div>

        <Table<OperationLog>
          rowKey="id"
          size="middle"
          loading={isLoading}
          columns={columns}
          dataSource={data?.list}
          // 「加载失败」≠「没有日志」：显式错误文案 + 重试入口
          locale={{ emptyText: isError ? <QueryErrorText error={error} onRetry={() => void refetch()} /> : '暂无操作日志' }}
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
      </Card>

      <Drawer title="日志详情" width={640} open={Boolean(detail)} onClose={() => setDetail(null)}>
        {detail && (
          <>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label="操作人">{detail.operator}</Descriptions.Item>
              <Descriptions.Item label="模块">{detail.module}</Descriptions.Item>
              <Descriptions.Item label="操作">{detail.action}</Descriptions.Item>
              <Descriptions.Item label="IP">{detail.ip}</Descriptions.Item>
              <Descriptions.Item label="时间" span={2}>
                {detail.createdAt}（耗时 {detail.duration}ms）
              </Descriptions.Item>
              <Descriptions.Item label="内容" span={2}>
                {detail.content}
              </Descriptions.Item>
            </Descriptions>
            <Row gutter={16} style={{ marginTop: 16 }}>
              <Col span={12}>
                <Card type="inner" title="操作前">
                  <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                    {detail.beforeData ? JSON.stringify(detail.beforeData, null, 2) : '-'}
                  </pre>
                </Card>
              </Col>
              <Col span={12}>
                <Card type="inner" title="操作后">
                  <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>
                    {detail.afterData ? JSON.stringify(detail.afterData, null, 2) : '-'}
                  </pre>
                </Card>
              </Col>
            </Row>
          </>
        )}
      </Drawer>
    </>
  );
}
