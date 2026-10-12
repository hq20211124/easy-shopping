/**
 * 数据字典（plans/admin-frontend.md 12.4；接口 admin-backend 2.5.2）
 *  - 左：字典类型列表（分页查询）；右：字典项列表（选中类型联动）
 *  - 订单状态、支付方式、退款原因等枚举统一从字典下发，各端禁止硬编码（1.7 / 12.4）
 *  - TODO(联调)：字典项 CRUD（POST/PUT/DELETE /admin/system/dict）就绪后补充新增/编辑表单，P0 为只读
 */
import { useEffect, useMemo, useState } from 'react';
import { Card, Col, Input, Row, Table, Tabs, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { listDictItems, listDictTypes } from '@/api/system';
import { normalizePage } from '@/utils/query';

export default function SystemDict() {
  const [keyword, setKeyword] = useState('');
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [activeType, setActiveType] = useState<string>('');

  const pageQuery = useMemo(() => ({ ...normalizePage(pageNum, pageSize), keyword }), [pageNum, pageSize, keyword]);

  const { data, isLoading } = useQuery({
    queryKey: ['dict-types', pageQuery],
    queryFn: () => listDictTypes(pageQuery),
    placeholderData: (prev) => prev,
  });

  const currentType = activeType || data?.list[0]?.type || '';

  const { data: items, isLoading: itemsLoading } = useQuery({
    queryKey: ['dict-items', currentType],
    queryFn: () => listDictItems(currentType),
    enabled: Boolean(currentType),
  });

  useEffect(() => {
    if (!activeType && data?.list[0]) setActiveType(data.list[0].type);
  }, [activeType, data]);

  return (
    <Row gutter={16}>
      <Col xs={24} lg={10}>
        <Card
          className="page-card"
          title="字典类型"
          extra={
            <Input.Search
              placeholder="名称 / 编码"
              allowClear
              size="small"
              style={{ width: 200 }}
              onSearch={(value) => {
                setKeyword(value.trim());
                setPageNum(1);
              }}
            />
          }
        >
          <Table
            rowKey="type"
            size="small"
            loading={isLoading}
            dataSource={data?.list}
            onRow={(record) => ({ onClick: () => setActiveType(record.type), style: { cursor: 'pointer' } })}
            columns={[
              { title: '名称', dataIndex: 'name' },
              { title: '编码', dataIndex: 'type' },
              {
                title: '状态',
                dataIndex: 'status',
                width: 80,
                render: (v: number) => (v === 1 ? '启用' : '停用'),
              },
            ]}
            pagination={{
              current: data?.pageNum ?? pageNum,
              pageSize: data?.pageSize ?? pageSize,
              total: data?.total ?? 0,
              size: 'small',
              showSizeChanger: false,
              onChange: (p, ps) => {
                setPageNum(p);
                setPageSize(ps);
              },
            }}
          />
        </Card>
      </Col>
      <Col xs={24} lg={14}>
        <Card className="page-card" title={`字典项 · ${currentType || '-'}`}>
          <Tabs
            items={[
              {
                key: 'items',
                label: '字典项列表',
                children: (
                  <>
                    <Typography.Paragraph type="secondary">
                      前端下拉选项统一从字典接口获取（12.4）；新增字典项表单待后端字典 CRUD 接口联调后接入。
                    </Typography.Paragraph>
                    <Table
                      rowKey="id"
                      size="small"
                      loading={itemsLoading}
                      dataSource={items ?? []}
                      pagination={false}
                      columns={[
                        { title: '排序', dataIndex: 'sort', width: 80 },
                        { title: '编码', dataIndex: 'code', width: 140 },
                        { title: '名称', dataIndex: 'label' },
                        {
                          title: '状态',
                          dataIndex: 'status',
                          width: 80,
                          render: (v: number) => (v === 1 ? '启用' : '停用'),
                        },
                      ]}
                    />
                  </>
                ),
              },
            ]}
          />
        </Card>
      </Col>
    </Row>
  );
}
