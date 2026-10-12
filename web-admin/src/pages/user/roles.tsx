/**
 * 角色权限（plans/admin-frontend.md 12.1 / 2.2；接口 admin-backend 2.5.2）
 *  - 角色列表 + 权限分配抽屉：菜单权限 + 按钮权限点树（2.2.3 权限点注册表）
 *  - 数据权限档位展示（2.3.1）；前端只控入口可见性，数据隔离由后端 SQL 拦截保证
 *  - 内置角色（super_admin）不可编辑；授权后后端刷新相关用户 permVer（2.1.4）
 */
import { useMemo, useState } from 'react';
import { Button, Card, Drawer, message, Space, Table, Tree, Typography, type TableColumnsType, type TreeProps } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getPermissionTree,
  getRolePermissions,
  listRoles,
  updateRolePermissions,
  type PermissionTreeNode,
  type Role,
} from '@/api/user';
import { DATA_SCOPE_OPTIONS } from '@/config/constants';
import { AuthButton } from '@/components/AuthButton';
import { PageLoading } from '@/components/PageLoading';
import { QueryErrorText } from '@/components/QueryError';
import { normalizePage } from '@/utils/query';

/** 权限点树 → antd Tree 数据 */
function toTreeData(nodes: PermissionTreeNode[]): NonNullable<TreeProps['treeData']> {
  return nodes.map((node) => ({
    key: node.key,
    title: node.title,
    children: node.children ? toTreeData(node.children) : undefined,
  }));
}

/** 叶子权限点 key 集合（勾选只落在权限点/菜单叶子上，父级由 Tree 半选机制处理） */
function collectLeafKeys(nodes: PermissionTreeNode[], acc: string[] = []): string[] {
  nodes.forEach((node) => {
    if (!node.children?.length) acc.push(node.key);
    else collectLeafKeys(node.children, acc);
  });
  return acc;
}

export default function UserRoles() {
  const queryClient = useQueryClient();
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [activeRole, setActiveRole] = useState<Role | null>(null);
  const [checkedKeys, setCheckedKeys] = useState<string[]>([]);

  const pageQuery = useMemo(() => normalizePage(pageNum, pageSize), [pageNum, pageSize]);

  const { data, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['roles', pageQuery],
    queryFn: () => listRoles(pageQuery),
    placeholderData: (prev) => prev,
  });

  const {
    data: tree,
    isLoading: treeLoading,
    isError: treeError,
    error: treeErrorValue,
    refetch: refetchTree,
  } = useQuery({
    queryKey: ['permission-tree'],
    queryFn: getPermissionTree,
  });

  const grantMutation = useMutation({
    mutationFn: ({ id, permissions }: { id: number; permissions: string[] }) =>
      updateRolePermissions(id, permissions),
    onSuccess: () => {
      message.success('权限已更新，相关用户 Token 将被拉黑并刷新权限版本（2.1.4）');
      setActiveRole(null);
      void queryClient.invalidateQueries({ queryKey: ['roles'] });
    },
  });

  const openDrawer = async (row: Role) => {
    setActiveRole(row);
    try {
      const { permissions } = await getRolePermissions(row.id);
      const leaves = new Set(collectLeafKeys(tree ?? []));
      // 只回显权限点（叶子）key，菜单 key 交由 Tree 自动勾选父级
      setCheckedKeys(permissions.filter((p) => leaves.has(p)));
    } catch (err) {
      // 拉取已授权失败时不能让抽屉开出一个"全未勾选"的假象，用户一保存就会覆盖权限
      message.error(`读取角色权限失败：${(err as Error | null)?.message ?? '未知错误'}`);
      setCheckedKeys([]);
    }
  };

  const columns: TableColumnsType<Role> = [
    { title: '角色名称', dataIndex: 'name', width: 160 },
    { title: '角色编码', dataIndex: 'code', width: 180 },
    {
      title: '数据范围',
      dataIndex: 'dataScope',
      width: 150,
      render: (v: number) => DATA_SCOPE_OPTIONS.find((o) => o.value === v)?.label ?? v,
    },
    { title: '用户数', dataIndex: 'userCount', width: 90 },
    { title: '说明', dataIndex: 'remark', ellipsis: true },
    {
      title: '操作',
      key: 'action',
      fixed: 'right',
      width: 140,
      render: (_, row: Role) =>
        row.builtin ? (
          <Typography.Text type="secondary">内置角色</Typography.Text>
        ) : (
          <AuthButton permission="system:role" type="link" size="small" onClick={() => void openDrawer(row)}>
            权限分配
          </AuthButton>
        ),
    },
  ];

  return (
    <>
      <Card className="page-card" title="角色权限">
        <Table<Role>
          rowKey="id"
          size="middle"
          loading={isLoading}
          columns={columns}
          dataSource={data?.list}
          // 「加载失败」≠「没有角色」：显式错误文案 + 重试入口
          locale={{ emptyText: isError ? <QueryErrorText error={error} onRetry={() => void refetch()} /> : '暂无角色' }}
          scroll={{ x: 1000 }}
          pagination={{
            current: data?.pageNum ?? pageNum,
            pageSize: data?.pageSize ?? pageSize,
            total: data?.total ?? 0,
            showSizeChanger: true,
            pageSizeOptions: [10, 20, 50, 100],
            showTotal: (total) => `共 ${total} 条`,
            onChange: (p, ps) => {
              setPageNum(p);
              setPageSize(ps);
            },
          }}
        />
      </Card>

      <Drawer
        title={`权限分配 · ${activeRole?.name ?? ''}`}
        width={420}
        open={Boolean(activeRole)}
        onClose={() => setActiveRole(null)}
        extra={
          <Space>
            <Button onClick={() => setActiveRole(null)}>取消</Button>
            <Button
              type="primary"
              loading={grantMutation.isPending}
              onClick={() => {
                if (activeRole) {
                  grantMutation.mutate({ id: activeRole.id, permissions: checkedKeys });
                }
              }}
            >
              保存
            </Button>
          </Space>
        }
      >
        {treeLoading ? (
          <PageLoading />
        ) : treeError ? (
          <QueryErrorText error={treeErrorValue} onRetry={() => void refetchTree()} />
        ) : (
          <>
            <Typography.Paragraph type="secondary">
              勾选菜单与按钮权限点（权限码以后端 2.2.3 注册表为准）；保存后后端将相关用户 Token 拉黑并提升
              permVer，前端每 5 分钟静默刷新权限。
            </Typography.Paragraph>
            <Tree
              checkable
              defaultExpandAll
              selectable={false}
              treeData={toTreeData(tree ?? [])}
              checkedKeys={checkedKeys}
              onCheck={(keys) => setCheckedKeys(keys as unknown as string[])}
            />
          </>
        )}
      </Drawer>
    </>
  );
}
