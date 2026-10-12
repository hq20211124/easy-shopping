/** 500 服务异常页（plans/admin-frontend.md 1.3） */
import { useNavigate } from 'react-router-dom';
import { Button, Result } from 'antd';

export default function ServerError() {
  const navigate = useNavigate();
  return (
    <Result
      status="500"
      title="500"
      subTitle="服务暂时不可用，请稍后重试；若持续出现请联系管理员并提供 traceId 便于排查"
      extra={
        <div style={{ display: 'flex', gap: 8, justifyContent: 'center' }}>
          <Button onClick={() => window.location.reload()}>重新加载</Button>
          <Button type="primary" onClick={() => navigate('/dashboard')}>
            返回工作台
          </Button>
        </div>
      }
    />
  );
}
