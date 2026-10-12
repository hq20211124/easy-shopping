/**
 * 查询失败的可视化出口（静默失败治理）
 *
 * 背景：`request.ts` 对业务/网络错误已统一弹 toast，但**页面本身**若不区分
 * 「没有数据」与「接口挂了」，用户只会看到一个空表格 / 永久转圈的骨架屏，
 * 无法判断该不该重试。本组件统一给出「错误原因 + 重试入口」。
 *
 * 两种用法：
 *  - `<QueryError error={error} onRetry={refetch} />` 整页替换（详情页：原来永远转圈）
 *  - `locale={{ emptyText: <QueryErrorText ... /> }}` 表格空态（列表页）
 */
import { Button, Result, Space } from 'antd';

/** 抽取可读错误文案：ApiError 已有 message，这里只做兜底防 undefined */
function errorText(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'string' && error) return error;
  return '请求失败，请稍后重试';
}

/** 表格/列表内嵌的紧凑错误态（默认空表格时显示「加载失败 + 重试」） */
export function QueryErrorText({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  return (
    <Space direction="vertical">
      <span>加载失败：{errorText(error)}</span>
      <Button size="small" onClick={onRetry}>
        重试
      </Button>
    </Space>
  );
}

/** 整页错误态（详情页/工作台：替换永久骨架屏） */
export default function QueryError({ error, onRetry, title = '加载失败' }: { error: unknown; onRetry: () => void; title?: string }) {
  return (
    <Result
      status="error"
      title={title}
      subTitle={errorText(error)}
      extra={
        <Button type="primary" onClick={onRetry}>
          重试
        </Button>
      }
    />
  );
}
