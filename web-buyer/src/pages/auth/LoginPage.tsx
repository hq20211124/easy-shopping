/** 登录/注册：手机号+验证码（P0 测试码固定 123456）注册；已有账号走密码登录。 */

import { type FormEvent, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { authApi } from '@/api/auth';
import { useAuthStore } from '@/store/auth';
import { toast } from '@/store/toast';

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const setSession = useAuthStore((s) => s.setSession);
  const from = (location.state as { from?: string } | null)?.from ?? '/';

  const [tab, setTab] = useState<'register' | 'login'>('register');
  const [mobile, setMobile] = useState('');
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const timer = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => () => { if (timer.current) clearInterval(timer.current); }, []);

  const sendCode = () => {
    if (!/^1[3-9]\d{9}$/.test(mobile)) {
      toast.error('请输入正确的手机号');
      return;
    }
    // P0 无短信服务（core-user TODO P1），本地倒计时模拟发送
    setCountdown(60);
    timer.current = setInterval(() => setCountdown((c) => (c <= 1 ? 0 : c - 1)), 1000);
    toast.info('测试环境验证码固定为 123456');
  };

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (loading) return;
    setLoading(true);
    try {
      const token =
        tab === 'register'
          ? await authApi.register({ mobile, code, password })
          : await authApi.login({ username: mobile, password });
      setSession(token);
      toast.success(token.isNewUser ? '注册成功，已自动登录' : '登录成功');
      navigate(from, { replace: true });
    } catch {
      // 业务错误已由 API 层 Toast 响应 msg
    } finally {
      setLoading(false);
    }
  };

  const phoneValid = /^1[3-9]\d{9}$/.test(mobile);
  const canSubmit = phoneValid && password.length >= 6 && (tab === 'login' || code.length > 0);

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-red-50 via-white to-orange-50 px-4">
      <div className="w-full max-w-4xl overflow-hidden rounded-2xl bg-white shadow-xl md:flex">
        <div className="hidden w-1/2 flex-col justify-between bg-gradient-to-br from-red-500 to-orange-400 p-10 text-white md:flex">
          <span className="text-2xl font-bold">惠购商城</span>
          <div>
            <p className="text-3xl font-bold leading-relaxed">精选好物</p>
            <p className="text-3xl font-bold leading-relaxed">品质生活</p>
            <p className="mt-4 text-sm text-white/80">P0 演示环境 · 验证码固定 123456</p>
          </div>
          <span className="text-xs text-white/60">EasyShopping Buyer</span>
        </div>
        <div className="w-full p-8 md:w-1/2">
          <div className="mb-6 flex gap-6 border-b border-gray-100">
            {(['register', 'login'] as const).map((t) => (
              <button
                key={t}
                type="button"
                onClick={() => setTab(t)}
                className={`-mb-px border-b-2 pb-2 text-base ${
                  tab === t ? 'border-red-500 font-semibold text-red-600' : 'border-transparent text-gray-400'
                }`}
              >
                {t === 'register' ? '注册' : '登录'}
              </button>
            ))}
          </div>
          <form onSubmit={onSubmit} className="flex flex-col gap-4">
            <input
              value={mobile}
              onChange={(e) => setMobile(e.target.value.trim())}
              placeholder="手机号"
              inputMode="numeric"
              maxLength={11}
              className="h-11 rounded border border-gray-300 px-3 text-sm outline-none focus:border-red-400"
            />
            {tab === 'register' && (
              <div className="flex gap-2">
                <input
                  value={code}
                  onChange={(e) => setCode(e.target.value.trim())}
                  placeholder="短信验证码"
                  maxLength={6}
                  className="h-11 flex-1 rounded border border-gray-300 px-3 text-sm outline-none focus:border-red-400"
                />
                <button
                  type="button"
                  disabled={countdown > 0}
                  onClick={sendCode}
                  className="h-11 shrink-0 rounded border border-red-400 px-4 text-sm text-red-500 disabled:border-gray-200 disabled:text-gray-300"
                >
                  {countdown > 0 ? `${countdown}s` : '获取验证码'}
                </button>
              </div>
            )}
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder={tab === 'register' ? '设置登录密码（6~32 位）' : '密码'}
              className="h-11 rounded border border-gray-300 px-3 text-sm outline-none focus:border-red-400"
            />
            <button
              type="submit"
              disabled={!canSubmit || loading}
              className="h-11 rounded bg-red-500 text-sm font-semibold text-white hover:bg-red-600 disabled:bg-red-200"
            >
              {loading ? '提交中…' : tab === 'register' ? '注册并登录' : '登录'}
            </button>
            <p className="text-center text-xs text-gray-400">
              {tab === 'register' ? '已有账号？点击左上「登录」' : '没有账号？点击左上「注册」'}
            </p>
          </form>
        </div>
      </div>
    </div>
  );
}
