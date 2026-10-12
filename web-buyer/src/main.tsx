import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router-dom';
import { router } from './router';
import { ToastHost } from '@/components/common/ToastHost';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 业务错误（3001/3002/3003 等）重试无意义；网络错误最多重试 1 次
      retry: (failureCount, error) => failureCount < 1 && (error as { code?: number })?.code === undefined,
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
    mutations: { retry: false },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
      <ToastHost />
    </QueryClientProvider>
  </StrictMode>,
);
