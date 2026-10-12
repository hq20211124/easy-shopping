/**
 * 登录页（plans/admin-frontend.md 2.1）
 *  - 账号密码登录（短信/扫码登录为 P1，后端接口与买家端扫码协议复用 core-user，见 admin-backend 2.5.1）
 *  - Access Token 存内存，refresh 走 httpOnly Cookie；P0 后端仅 bearer 时用 sessionStorage 兜底（见 utils/token.ts TODO）
 *  - 连续失败 3 次后端强制图形验证码、5 次锁定 15 分钟（admin-backend 2.5.1），错误文案由后端下发
 */
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Alert, Button, Card, Form, Input, message } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { useAuthStore } from '@/stores/auth';
import type { LoginParams } from '@/api/auth';

const MOCK_ENABLED = import.meta.env.VITE_USE_MOCK !== 'false';

export default function Login() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const login = useAuthStore((s) => s.login);
  const [submitting, setSubmitting] = useState(false);

  const onFinish = async (values: LoginParams) => {
    setSubmitting(true);
    try {
      await login(values);
      message.success('登录成功');
      const redirect = searchParams.get('redirect');
      navigate(redirect && redirect.startsWith('/') ? redirect : '/dashboard', { replace: true });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(135deg, #1677ff 0%, #69b1ff 100%)',
      }}
    >
      <Card style={{ width: 400 }} title="电商平台管理端">
        {MOCK_ENABLED && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="当前为 Mock 联调环境"
            description="任意账号 + 4 位以上密码即可登录（仅开发期生效）。"
          />
        )}
        <Form<LoginParams> onFinish={onFinish} initialValues={{ username: 'admin', password: '123456' }} size="large">
          <Form.Item name="username" rules={[{ required: true, message: '请输入账号' }]}>
            <Input prefix={<UserOutlined />} placeholder="账号" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={submitting}>
            登录
          </Button>
        </Form>
      </Card>
    </div>
  );
}
