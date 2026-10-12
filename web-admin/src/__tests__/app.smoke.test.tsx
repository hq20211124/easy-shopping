// @vitest-environment jsdom
/**
 * 应用渲染冒烟测试：确认路由与布局可正常渲染（无运行时异常、无白屏）
 * 覆盖点：App 根组件树、antd ConfigProvider、React Router v7 data router、React.lazy + Suspense
 */
import { beforeAll, describe, expect, it } from 'vitest';
import { act } from 'react';
import { createRoot } from 'react-dom/client';

/**
 * jsdom 环境兼容补丁：
 * react-router 导航时用 jsdom 的 AbortSignal 构造 Request，而 vitest jsdom 环境下
 * Request 来自 Node(undici)，undici 严格校验 signal 实例类型会抛
 * "RequestInit: Expected signal to be an instance of AbortSignal"，导致导航中断、页面空白。
 * 这里包装 Request，遇到不兼容的 signal 时剥离（冒烟测试不依赖请求中断语义）。
 */
const NativeRequest = globalThis.Request;
function isCompatibleSignal(signal: AbortSignal): boolean {
  try {
    new NativeRequest('http://localhost/', { signal });
    return true;
  } catch {
    return false;
  }
}
globalThis.Request = class PatchedRequest extends NativeRequest {
  constructor(input: RequestInfo | URL, init: RequestInit = {}) {
    if (init.signal && !isCompatibleSignal(init.signal)) {
      const { signal: _signal, ...rest } = init;
      super(input, rest);
      return;
    }
    super(input, init);
  }
} as unknown as typeof Request;

// jsdom 缺失的浏览器 API（antd 依赖）
beforeAll(async () => {
  const w = window as unknown as Record<string, unknown>;
  if (!w.matchMedia) {
    w.matchMedia = (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    });
  }
  if (!w.ResizeObserver) {
    w.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  }
  if (!w.scrollTo) w.scrollTo = () => undefined;
  (globalThis as unknown as Record<string, unknown>).IS_REACT_ACT_ENVIRONMENT = true;
  // 预热模块转换缓存：冷启动时 antd/页面模块 transform 较慢，避免首个用例超时
  await Promise.all([import('@/App'), import('@/pages/login'), import('@/pages/error/404')]);
});

/**
 * 渲染应用并轮询等待目标文案出现（覆盖模块转换、懒加载 chunk、mock 请求延迟）。
 * 超时未出现时抛出带 DOM 片段的错误，便于定位白屏/渲染异常。
 */
async function renderApp(path: string, expected: string[]): Promise<string> {
  window.history.pushState({}, '', path);
  const container = document.createElement('div');
  document.body.appendChild(container);
  // 动态导入：确保 polyfill 先于 antd / 应用模块初始化生效
  const { default: App } = await import('@/App');
  let root: ReturnType<typeof createRoot> | undefined;
  await act(async () => {
    root = createRoot(container);
    root.render(<App />);
  });
  const hit = () => expected.every((t) => container.textContent?.includes(t));
  for (let i = 0; i < 160 && !hit(); i++) {
    await act(async () => {
      await new Promise((r) => setTimeout(r, 250));
    });
  }
  const text = container.textContent ?? '';
  const html = container.innerHTML.slice(0, 1000);
  const passed = expected.every((t) => text.includes(t));
  // 卸载组件并等待挂起的定时器（antd Form useDebounce）落火，
  // 避免其在 jsdom 环境销毁后触发、产生 unhandled error
  await act(async () => {
    root?.unmount();
    await new Promise((r) => setTimeout(r, 500));
  });
  container.remove();
  if (!passed) {
    throw new Error(`[render ${path}] 未出现预期文案 ${JSON.stringify(expected)}，DOM 片段：${html}`);
  }
  return text;
}

describe('应用渲染冒烟', () => {
  it('/login 渲染品牌与登录表单', async () => {
    const text = await renderApp('/login', ['电商平台管理端', '密码']);
    expect(text).toContain('电商平台管理端');
    expect(text).toContain('密码');
  }, 60_000);

  it('未登录访问根路径时跳转到登录页（路由守卫生效，不白屏）', async () => {
    const text = await renderApp('/', ['登录']);
    expect(text).toContain('登录');
  }, 60_000);

  it('未知路径不白屏（跳登录或 404 兜底）', async () => {
    const text = await renderApp('/not-exist-page', ['登录']);
    expect(text.length).toBeGreaterThan(0);
  }, 60_000);
});
