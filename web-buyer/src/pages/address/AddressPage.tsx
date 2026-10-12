/** 地址管理：列表 + 新增/编辑表单 + 删除 + 设为默认。 */

import { type FormEvent, useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { userApi } from '@/api/user';
import { toast } from '@/store/toast';
import type { Address, AddressPayload } from '@/types/domain';

const emptyForm: AddressPayload = {
  name: '',
  mobile: '',
  province: '',
  city: '',
  district: '',
  detail: '',
  isDefault: false,
};

export function AddressPage() {
  const queryClient = useQueryClient();
  const { data: addresses, isLoading } = useQuery({ queryKey: ['addresses'], queryFn: userApi.listAddresses });
  const [editing, setEditing] = useState<Address | 'new' | null>(null);
  const [form, setForm] = useState<AddressPayload>(emptyForm);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['addresses'] });

  const save = useMutation({
    mutationFn: async () => {
      if (editing === 'new') await userApi.createAddress(form);
      else if (editing) await userApi.updateAddress(editing.id, form);
    },
    onSuccess: () => {
      toast.success('地址已保存');
      setEditing(null);
      refresh();
    },
  });

  const remove = useMutation({
    mutationFn: (id: number) => userApi.removeAddress(id),
    onSuccess: () => {
      toast.success('地址已删除');
      refresh();
    },
  });

  const setDefault = useMutation({
    mutationFn: (id: number) => userApi.setDefaultAddress(id),
    onSuccess: () => refresh(),
  });

  useEffect(() => {
    if (editing === 'new') setForm(emptyForm);
    else if (editing) setForm({ ...editing, district: editing.district ?? '', zipCode: editing.zipCode ?? undefined });
  }, [editing]);

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (!/^1[3-9]\d{9}$/.test(form.mobile)) {
      toast.error('请输入正确的手机号');
      return;
    }
    if (!form.province || !form.city || !form.detail.trim()) {
      toast.error('请填写完整的省市区与详细地址');
      return;
    }
    save.mutate();
  };

  const setField = (patch: Partial<AddressPayload>) => setForm((f) => ({ ...f, ...patch }));

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold text-gray-800">收货地址</h1>
        {editing === null && (
          <button
            type="button"
            onClick={() => setEditing('new')}
            className="rounded bg-red-500 px-4 py-2 text-sm text-white hover:bg-red-600"
          >
            + 新增地址
          </button>
        )}
      </div>

      {editing !== null && (
        <form onSubmit={onSubmit} className="flex flex-col gap-3 rounded-lg border border-gray-200 bg-white p-5">
          <h2 className="text-sm font-semibold text-gray-700">{editing === 'new' ? '新增地址' : '编辑地址'}</h2>
          <div className="grid grid-cols-2 gap-3">
            <input placeholder="收货人" value={form.name} onChange={(e) => setField({ name: e.target.value })} className={inputCls} />
            <input placeholder="手机号" maxLength={11} value={form.mobile} onChange={(e) => setField({ mobile: e.target.value.trim() })} className={inputCls} />
            <input placeholder="省份" value={form.province} onChange={(e) => setField({ province: e.target.value })} className={inputCls} />
            <input placeholder="城市" value={form.city} onChange={(e) => setField({ city: e.target.value })} className={inputCls} />
            <input placeholder="区县（选填）" value={form.district ?? ''} onChange={(e) => setField({ district: e.target.value })} className={inputCls} />
            <input placeholder="邮编（选填）" value={form.zipCode ?? ''} onChange={(e) => setField({ zipCode: e.target.value })} className={inputCls} />
          </div>
          <textarea placeholder="详细地址（街道/楼栋/门牌号）" value={form.detail} onChange={(e) => setField({ detail: e.target.value })} className={`${inputCls} h-20`} />
          <label className="flex items-center gap-2 text-sm text-gray-600">
            <input type="checkbox" checked={form.isDefault ?? false} onChange={(e) => setField({ isDefault: e.target.checked })} />
            设为默认地址
          </label>
          <div className="flex justify-end gap-3">
            <button type="button" onClick={() => setEditing(null)} className="rounded border border-gray-300 px-5 py-2 text-sm text-gray-500">
              取消
            </button>
            <button type="submit" disabled={save.isPending} className="rounded bg-red-500 px-5 py-2 text-sm text-white hover:bg-red-600 disabled:bg-red-200">
              {save.isPending ? '保存中…' : '保存'}
            </button>
          </div>
        </form>
      )}

      {isLoading ? (
        <p className="py-16 text-center text-gray-400">加载中…</p>
      ) : (addresses ?? []).length === 0 ? (
        <p className="py-16 text-center text-gray-400">还没有收货地址，点击右上角新增</p>
      ) : (
        <div className="flex flex-col gap-3">
          {(addresses ?? []).map((a: Address) => (
            <div key={a.id} className="rounded-lg border border-gray-200 bg-white p-4">
              <div className="flex items-center gap-3 text-sm">
                <span className="font-semibold text-gray-800">{a.name}</span>
                <span className="text-gray-500">{a.mobile}</span>
                {a.isDefault && <span className="rounded bg-red-100 px-1.5 text-xs text-red-500">默认</span>}
              </div>
              <p className="mt-1 text-sm text-gray-500">
                {a.province}
                {a.city}
                {a.district ?? ''} {a.detail}
              </p>
              <div className="mt-3 flex gap-4 text-xs">
                {!a.isDefault && (
                  <button type="button" onClick={() => setDefault.mutate(a.id)} className="text-gray-400 hover:text-red-500">
                    设为默认
                  </button>
                )}
                <button type="button" onClick={() => setEditing(a)} className="text-gray-400 hover:text-red-500">
                  编辑
                </button>
                <button
                  type="button"
                  onClick={() => window.confirm('确定删除该地址吗？') && remove.mutate(a.id)}
                  className="text-gray-400 hover:text-red-500"
                >
                  删除
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

const inputCls =
  'h-10 rounded border border-gray-300 px-3 text-sm outline-none focus:border-red-400';
