/** 403 无权限页（plans/admin-frontend.md 1.3 / 2.4） */
import { useNavigate } from 'react-router-dom';
import { Button, Result } from 'antd';

export default function NoPermission() {
  const navigate = useNavigate();
  return (
    <Result
      status="403"
      title="403"
      subTitle="抱歉，您没有访问该页面的权限。请联系管理员为您的角色分配对应权限点（如 product:list / order:list）。"
      extra={
        <Button type="primary" onClick={() => navigate('/dashboard')}>
          返回工作台
        </Button>
      }
    />
  );
}
