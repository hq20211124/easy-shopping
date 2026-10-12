import { defineConfig } from 'vitest/config';
import { loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
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
    plugins: [react(), tailwindcss(), ...(useMock ? [mockApiPlugin()] : [])],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src'),
      },
    },
    server: {
      port: 5174,
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
            vendor: ['react', 'react-dom', 'react-router-dom', '@tanstack/react-query'],
          },
        },
      },
    },
    test: {
      environment: 'node',
      include: ['src/**/*.test.{ts,tsx}'],
    },
  };
});
