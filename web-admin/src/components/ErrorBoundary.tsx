/**
 * 全局异常兜底（plans/admin-frontend.md 1.3 错误兜底）
 * 捕获渲染异常，展示 500 页并提供重试入口；TODO(监控)：接入 Sentry 时在此上报
 */
import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Button, Result } from 'antd';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // TODO(监控)：上报 Sentry / 自建前端监控（admin-frontend 16.3 异常监控）
    console.error('[ErrorBoundary]', error, info.componentStack);
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    return (
      <Result
        status="500"
        title="页面出现异常"
        subTitle={`错误信息：${error.message}`}
        extra={
          <Button type="primary" onClick={() => window.location.reload()}>
            重新加载
          </Button>
        }
      />
    );
  }
}
