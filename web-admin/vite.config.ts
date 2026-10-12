import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import { mockApiPlugin } from './mock';

// 环境变量：VITE_USE_MOCK=false 时直连后端（dev 下经 proxy 转发到网关 :8080）
const useMock = process.env.VITE_USE_MOCK !== 'false';

export default defineConfig({
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
  },
});
