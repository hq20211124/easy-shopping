/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API BaseURL：开发同源 /api（Vite 中间件 mock 或 proxy 到网关 :8080）；生产同源 /api 由 Nginx 反代 */
  readonly VITE_API_BASE: string;
  /** 是否启用 Vite mock 中间件（仅开发期生效） */
  readonly VITE_USE_MOCK: string;
  /** Access Token 存储策略：memory=仅内存（正式口径）| session=P0 联调兜底 */
  readonly VITE_ACCESS_TOKEN_STORAGE: 'memory' | 'session';
  readonly VITE_APP_TITLE: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
