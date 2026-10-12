import { defineConfig } from 'vitest/config';
import { loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import { mockApiPlugin } from './mock';

// 环境变量：VITE_USE_MOCK=false 时直连后端（dev 下经 proxy 转发到网关 :8080）
// 必须用 loadEnv 读 .env.<mode>：vite.config 求值时 process.env 里没有 .env 文件的变量，
// 只改 .env.development 的话 mock 插件不会卸载、proxy 也不会挂上。
function resolveUseMock(mode: string): boolean {
  const env = loadEnv(mode, process.cwd(), '');
  return (process.env.VITE_USE_MOCK ?? env.VITE_USE_MOCK) !== 'false';
}

export default defineConfig(({ mode }) => {
  const useMock = resolveUseMock(mode);
  return {
    plugins: [react(), ...(useMock ? [mockApiPlugin()] : [])],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src'),
      },
    },
    server: {
      port: 5173,
      proxy: useMock
        ? undefined
        : {
            '/api': {
              target: 'http://localhost:8080',
              changeOrigin: true,
            },
          },
    },
    build: {
      sourcemap: false,
      chunkSizeWarningLimit: 1200,
      rollupOptions: {
        output: {
          manualChunks: {
            vendor: ['react', 'react-dom', 'react-router-dom'],
            antd: ['antd', '@ant-design/icons'],
          },
        },
      },
    },
    test: {
      environment: 'node',
      include: ['src/**/*.test.{ts,tsx}'],
      // 冷启动时 antd/App 模块 transform 较慢（collect 可达数十秒），
      // 默认 hookTimeout(10s) 会导致冒烟测试 beforeAll 预热超时
      hookTimeout: 120_000,
      testTimeout: 120_000,
      // 串行执行测试文件：并行时主线程同时为多个 worker transform 模块，
      // 慢机器上会超过 vitest 内部 60s 的 RPC fetch 超时（Timeout calling "fetch"）
      fileParallelism: false,
    },
  };
});
