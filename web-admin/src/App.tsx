/**
 * 应用根组件：antd 中文语言包 + 主题 + TanStack Query 缓存 + 路由
 *  - React 19 需配合 @ant-design/v5-patch-for-react-19（plans/admin-frontend.md 1.1 兼容性说明）
 */
import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { router } from '@/config/routes';

dayjs.locale('zh-cn');

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

export default function App() {
  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#1677ff' } }}>
          <AntApp>
            <RouterProvider router={router} />
          </AntApp>
        </ConfigProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  );
}
