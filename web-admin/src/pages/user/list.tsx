/**
 * 用户管理（管理员账号）（plans/admin-frontend.md 2.2 / 12.1；接口 admin-backend 2.5.2）
 *  - 列表 = 查询表格 + 分页；新增/编辑 = 表单弹窗
 *  - 删除为软删；重置密码需二次验证（后端约束，2.5.2）
 *  - 防提权：不可分配超出操作者自身数据范围的角色（2.2.2，后端校验为主）
 */
import { useMemo, useState } from 'react';
import {
  Card,
  Form,
  Input,
  message,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  type TableColumnsType,
} from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createAdminUser,
  deleteAdminUser,
  listAdminUsers,
  listRoles,
  updateAdminUser,
  type AdminUser,
} from '@/api/user';
import { AuthButton } from '@/components/AuthButton';
import { normalizePage } from '@/utils/query';

interface UserFormValues {
  username: string;
  nickname: string;
  password?: string;
  roles: string[];
}

export default function UserList() {
  const queryClient = useQueryClient();
  const [form] = Form.useForm<UserFormValues>();
  const [keyword, setKeyword] = useState('');
  const [pageNum, setPageNum] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [editing, setEditing] = useState<AdminUser | null>(null);
  const [modalOpen, setModalOpen] = useState(false);

  const pageQuery = useMemo(() => ({ ...normalizePage(pageNum, pageSize), keyword }), [pageNum, pageSize, keyword]);

  const { data, isLoading } = useQuery({
    queryKey: ['admin-users', pageQuery],
    queryFn: () => listAdminUsers(pageQuery),
    placeholderData: (prev) => prev,
  });

  const { data: roles } = useQuery({
    queryKey: ['roles', { pageNum: 1, pageSize: 100 }],
    queryFn: () => listRoles(normalizePage(1, 100)),
  });

  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ['admin-users'] });

  const saveMutation = useMutation({
    mutationFn: (values: UserFormValues) =>
      editing ? updateAdminUser(editing.id, values) : createAdminUser(values),
    onSuccess: () => {
      message.success(editing ? '用户已更新' : '用户已创建');
      setModalOpen(false);
      setEditing(null);
      form.resetFields();
      invalidate();
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteAdminUser(id),
    onSuccess: () => {
      message.success('用户已删除（软删）');
      invalidate();
    },
  });

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openEdit = (row: AdminUser) => {
    setEditing(row);
    form.setFieldsValue({ username: row.username, nickname: row.nickname, roles: row.roles });
    setModalOpen(true);
  };

  const columns: TableColumnsType<AdminUser> = [
    { title: '用户名', dataIndex: 'username', width: 140 },
    { title: '姓名', dataIndex: 'nickname', width: 120 },
    { title: '部门', dataIndex: 'deptName', width: 140 },
    {
      title: '角色',
      dataIndex: 'roles',
      render: (v: string[]) => v.map((role) => <Tag key={role}>{role}</Tag>),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (v: number) => <Tag color={v === 1 ? 'success' : 'default'}>{v === 1 ? '启用' : '禁用'}</Tag>,
    },
    { title: '最近登录', dataIndex: 'lastLoginAt', width: 170 },
    {
      title: '操作',
      key: 'action',
      fixed: 'right',
      width: 180,
      render: (_, row: AdminUser) => (
        <Space size={4}>
          <AuthButton permission="system:user" type="link" size="small" onClick={() => openEdit(row)}>
            编辑
          </AuthButton>
          <AuthButton
            permission="system:user"
            type="link"
            size="small"
            onClick={() => message.info('重置密码需二次验证（admin-backend 2.5.2），P0 由后端接口就绪后接入')}
          >
            重置密码
          </AuthButton>
          <Popconfirm title="确认删除该用户？" onConfirm={() => deleteMutation.mutate(row.id)}>
            <AuthButton permission="system:user" type="link" size="small" danger>
              删除
            </AuthButton>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card
      className="page-card"
      title="用户管理"
      extra={
        <AuthButton permission="system:user" type="primary" onClick={openCreate}>
          新增用户
        </AuthButton>
      }
    >
      <Space style={{ marginBottom: 16 }}>
        <Input.Search
          placeholder="用户名 / 姓名"
          allowClear
          style={{ width: 240 }}
          onSearch={(value) => {
            setKeyword(value.trim());
            setPageNum(1);
          }}
        />
      </Space>

      <Table<AdminUser>
        rowKey="id"
        size="middle"
        loading={isLoading}
        columns={columns}
        dataSource={data?.list}
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

      <Modal
        title={editing ? `编辑用户 · ${editing.username}` : '新增用户'}
        open={modalOpen}
        confirmLoading={saveMutation.isPending}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" onFinish={(values) => saveMutation.mutate(values)}>
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input placeholder="登录用户名" disabled={Boolean(editing)} />
          </Form.Item>
          <Form.Item name="nickname" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input placeholder="真实姓名 / 昵称" />
          </Form.Item>
          {!editing && (
            <Form.Item
              name="password"
              label="初始密码"
              rules={[{ required: true, min: 8, message: '初始密码至少 8 位' }]}
            >
              <Input.Password placeholder="首次登录后应强制修改" />
            </Form.Item>
          )}
          <Form.Item name="roles" label="分配角色" rules={[{ required: true, message: '请至少选择一个角色' }]}>
            <Select
              mode="multiple"
              placeholder="选择角色（不可分配超出自身数据范围的角色，2.2.2）"
              options={(roles?.list ?? []).map((role) => ({ label: role.name, value: role.code }))}
            />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
