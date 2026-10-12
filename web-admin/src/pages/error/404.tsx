/** 404 页面（plans/admin-frontend.md 1.3 兜底路由） */
import { useNavigate } from 'react-router-dom';
import { Button, Result } from 'antd';

export default function NotFound() {
  const navigate = useNavigate();
  return (
    <Result
      status="404"
      title="404"
      subTitle="您访问的页面不存在或已被移除"
      extra={
        <Button type="primary" onClick={() => navigate('/dashboard')}>
          返回工作台
        </Button>
      }
    />
  );
}
