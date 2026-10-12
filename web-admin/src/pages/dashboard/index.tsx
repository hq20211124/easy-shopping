/**
 * 工作台（plans/admin-frontend.md 3.1 / 3.2；接口 admin-backend 17.4 admin-data）
 *  - 统计卡片：今日 GMV / 订单数 / 新增用户 / 待处理事项（金额以「分」传输，展示走 formatAmount）
 *  - 待办聚合：待审核商品 / 待发货订单 / 待处理退款，角标数量 + 一键跳转
 *  - TODO(P1)：销售趋势图（ECharts 按需加载）、实时订单流（WebSocket + 200ms 合并时间窗）、运营日历
 *  - 数据权限联动：无权限指标由后端直接不返回（17.4 关键约束 1），前端不渲染对应卡片
 */
import { Link } from 'react-router-dom';
import { Card, Col, List, Row, Statistic, Tag, Typography } from 'antd';
import { ArrowUpOutlined, UnorderedListOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { getTodoList, getWorkbench } from '@/api/system';
import { formatAmount, formatNumber } from '@/utils/format';
import { PageLoading } from '@/components/PageLoading';
import QueryError from '@/components/QueryError';

export default function Dashboard() {
  const {
    data: workbench,
    isLoading,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ['workbench'],
    queryFn: getWorkbench,
    staleTime: 5 * 60_000, // 看板数据 5 分钟内不重复请求（前端 3.1）
  });
  const { data: todo } = useQuery({ queryKey: ['todo'], queryFn: getTodoList });

  // 加载失败必须显式报错：否则看板会渲染成一排 0，与「真实业务为 0」无法区分（静默失败）
  if (isError) {
    return <QueryError error={error} onRetry={() => void refetch()} title="工作台数据加载失败" />;
  }
  if (isLoading && !workbench) return <PageLoading />;

  return (
    <div>
      <Row gutter={16}>
        <Col xs={24} sm={12} lg={6}>
          <Card className="page-card">
            <Statistic
              title="今日 GMV（元）"
              value={formatAmount(workbench?.gmv)}
              suffix={<ArrowUpOutlined style={{ fontSize: 14, color: '#52c41a' }} />}
            />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card className="page-card">
            <Statistic title="今日订单数" value={formatNumber(workbench?.orderCount)} />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card className="page-card">
            <Statistic title="今日新增用户" value={formatNumber(workbench?.newMember)} />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card className="page-card">
            <Statistic title="待处理事项" value={formatNumber(workbench?.pendingCount)} />
          </Card>
        </Col>
      </Row>

      <Card
        className="page-card"
        title={
          <span>
            <UnorderedListOutlined style={{ marginRight: 8 }} />
            待办事项
          </span>
        }
      >
        <List
          dataSource={todo?.items ?? []}
          renderItem={(item) => (
            <List.Item
              actions={[
                <Link key="go" to={item.path}>
                  前往处理 →
                </Link>,
              ]}
            >
              <List.Item.Meta
                title={
                  <span>
                    {item.title}
                    <Tag color={item.count > 0 ? 'orange' : 'default'} style={{ marginLeft: 8 }}>
                      {item.count}
                    </Tag>
                  </span>
                }
                description={item.count > 0 ? '存在待处理数据，点击右侧入口尽快处理' : '暂无待处理数据'}
              />
            </List.Item>
          )}
        />
      </Card>

      <Card className="page-card" title="近 7 日趋势">
        <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
          数据来源：admin-data 预聚合表（17.4），T+1 离线口径；实时看板 P1 接入 WebSocket 推送。
          {' '}
          <br />
          注：P0 后端 `AdminDataController` 为 stub（gmv/orderCount 恒 0、趋势与待办恒空），示例数据仅 mock 环境可见。
        </Typography.Paragraph>
        <List
          grid={{ gutter: 12, column: 7 }}
          dataSource={workbench?.trend ?? []}
          renderItem={(trend) => (
            <List.Item style={{ textAlign: 'center' }}>
              <Statistic
                title={trend.date}
                value={formatAmount(trend.gmv)}
                valueStyle={{ fontSize: 16 }}
                suffix={
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {trend.orderCount} 单
                  </Typography.Text>
                }
              />
            </List.Item>
          )}
        />
      </Card>
    </div>
  );
}
