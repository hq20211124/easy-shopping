/**
 * 主布局：侧边菜单（按权限码过滤）+ 面包屑 + 内容区
 *
 *  - 侧边菜单优先取后端 GET /api/admin/auth/menus（RbacService 已按权限过滤），异常/空返回回退静态 MENUS
 *  - 侧边栏折叠状态记忆 localStorage（plans/admin-frontend.md 2.3 动态菜单交互要点；仅 UI 偏好，非敏感数据）
 *  - 面包屑基于路由 handle.title 自动生成（1.3）
 *  - AuthHandlerBridge 注册 401/403 全局处理：401 清态跳登录（保留 redirect），403 立即静默刷新权限（2.4）
 */
import { useEffect, useMemo, useState } from 'react';
import { Link, Outlet, useLocation, useMatches, useNavigate } from 'react-router-dom';
import { Breadcrumb, Button, Dropdown, Layout, Menu, Modal, theme, type MenuProps } from 'antd';
import { useQuery } from '@tanstack/react-query';
import {
  BarChartOutlined,
  FileOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  ShoppingCartOutlined,
  SettingOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { MENUS } from '@/config/menus';
import { getMenus, type AdminMenuNode } from '@/api/auth';
import { AFTER_SALE_AVAILABLE } from '@/api/order';
import { filterMenuByPermission, type PermissionMenuItem } from '@/utils/permission';
import { usePermission } from '@/hooks/usePermission';
import { useAuthStore } from '@/stores/auth';
import { registerAuthHandlers } from '@/api/request';

const COLLAPSE_KEY = 'admin_sidebar_collapsed';

/** 后端未实现的售后域入口（AFTER_SALE_AVAILABLE=false 时下线，后端菜单树与静态菜单都要过滤） */
const HIDDEN_MENU_PATHS = new Set<string>(AFTER_SALE_AVAILABLE ? [] : ['/order/refund']);

const ICON_MAP: Record<string, React.ReactNode> = {
  dashboard: <BarChartOutlined />,
  product: <FileOutlined />,
  order: <ShoppingCartOutlined />,
  system: <SettingOutlined />,
};

/** 后端菜单节点（RbacService.MenuNode）→ 前端菜单结构（null 字段剥离） */
function toPermissionMenus(nodes: AdminMenuNode[]): PermissionMenuItem[] {
  return nodes.map((node) => ({
    key: node.key,
    label: node.label,
    path: node.path ?? undefined,
    permission: node.permission ?? undefined,
    children: node.children?.length ? toPermissionMenus(node.children) : undefined,
  }));
}

/**
 * 功能开关下线：剔除后端未实现域的菜单项。
 * 后端 `DataInitializer` 仍会下发 `/order/refund`（权限点注册表已预埋），前端必须自行下线，
 * 否则菜单里会留下一个"点得进、取不到数"的入口。
 */
function hideDisabledMenus(items: PermissionMenuItem[]): PermissionMenuItem[] {
  return items.flatMap((item) => {
    if (item.path && HIDDEN_MENU_PATHS.has(item.path)) return [];
    const children = item.children?.length ? hideDisabledMenus(item.children) : undefined;
    if (item.children?.length && !children?.length && !item.permission) return [];
    return [{ ...item, children }];
  });
}

interface AppRouteHandle {
  title?: string;
}

export default function BasicLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const matches = useMatches();
  const { permissions } = usePermission();
  const profile = useAuthStore((s) => s.profile);
  const fetchProfile = useAuthStore((s) => s.fetchProfile);
  const logout = useAuthStore((s) => s.logout);
  const {
    token: { colorBgContainer },
  } = theme.useToken();

  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(COLLAPSE_KEY) === '1');

  // 动态菜单：GET /api/admin/auth/menus（后端已按权限过滤，admin-backend 2.5.1 / RbacService）；
  // 接口异常或空返回时回退静态 MENUS（两者菜单结构与权限码一致）
  const { data: remoteMenus } = useQuery({
    queryKey: ['auth-menus'],
    queryFn: getMenus,
    staleTime: 5 * 60_000,
    retry: false,
  });
  const sourceMenus = useMemo<PermissionMenuItem[]>(
    () => (remoteMenus?.length ? toPermissionMenus(remoteMenus) : MENUS),
    [remoteMenus],
  );

  useEffect(() => {
    localStorage.setItem(COLLAPSE_KEY, collapsed ? '1' : '0');
  }, [collapsed]);

  // 401/403 全局处理（admin-frontend 1.6 请求层规范）
  useEffect(
    () =>
      registerAuthHandlers(
        () => {
          useAuthStore.getState().reset();
          const redirect = encodeURIComponent(location.pathname + location.search);
          navigate(`/login?redirect=${redirect}`, { replace: true });
        },
        () => {
          // 403 → 立即重新拉取权限（2.4 权限变更生效策略）
          void fetchProfile().catch(() => undefined);
        },
      ),
    [navigate, location.pathname, location.search, fetchProfile],
  );

  const menuItems = useMemo(() => {
    const visible = filterMenuByPermission(hideDisabledMenus(sourceMenus), permissions);
    const toItems = (items: PermissionMenuItem[]): MenuProps['items'] =>
      items.map((item) =>
        item.children?.length
          ? { key: item.key, icon: ICON_MAP[item.key], label: item.label, children: toItems(item.children) }
          : { key: item.path ?? item.key, icon: ICON_MAP[item.key], label: item.label },
      );
    return toItems(visible);
  }, [permissions, sourceMenus]);

  const selectedKey = useMemo(() => {
    const flat: string[] = [];
    // 后端菜单树父节点也带 path（如 system → /system），因此父节点与子节点的 path 都要收集
    const walk = (items: PermissionMenuItem[]) =>
      items.forEach((m) => {
        if (m.path) flat.push(m.path);
        if (m.children?.length) walk(m.children);
      });
    walk(filterMenuByPermission(hideDisabledMenus(sourceMenus), permissions));
    const section = `/${location.pathname.split('/')[1]}/`;
    // 精确匹配优先；详情页（/product/1001 等）回退到所属模块的列表菜单
    return (
      flat.find((p) => p === location.pathname) ??
      flat.find((p) => p.startsWith(section)) ??
      'dashboard'
    );
  }, [location.pathname, permissions, sourceMenus]);

  const breadcrumbItems = useMemo(() => {
    const items = matches
      .map((m) => (m.handle as AppRouteHandle | undefined)?.title)
      .filter((t): t is string => Boolean(t));
    return items.map((title, i) =>
      i === items.length - 1 ? { title } : { title: <Link to={location.pathname}>{title}</Link> },
    );
  }, [matches, location.pathname]);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider trigger={null} collapsible collapsed={collapsed} breakpoint="lg">
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#fff',
            fontWeight: 600,
            fontSize: 16,
            whiteSpace: 'nowrap',
            overflow: 'hidden',
          }}
        >
          {collapsed ? '管理端' : '电商平台管理端'}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Layout.Sider>
      <Layout>
        <Layout.Header
          style={{
            padding: '0 16px',
            background: colorBgContainer,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Button
              type="text"
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => setCollapsed((c) => !c)}
            />
            <Breadcrumb items={breadcrumbItems} />
          </div>
          <Dropdown
            menu={{
              items: [{ key: 'logout', label: '退出登录', danger: true }],
              onClick: ({ key }) => {
                if (key !== 'logout') return;
                Modal.confirm({
                  title: '确认退出登录？',
                  content: '退出后将清空本地登录态，需重新登录。',
                  onOk: async () => {
                    await logout();
                    navigate('/login', { replace: true });
                  },
                });
              },
            }}
          >
            <span style={{ cursor: 'pointer' }}>
              <UserOutlined style={{ marginRight: 6 }} />
              {profile?.nickname ?? profile?.username ?? '未知用户'}
            </span>
          </Dropdown>
        </Layout.Header>
        <Layout.Content style={{ margin: 16 }}>
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
