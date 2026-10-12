import { api } from './client';
import type { Address, AddressPayload } from '@/types/domain';

export const userApi = {
  /** GET /api/v1/user/addresses（默认地址排首；出参手机号已脱敏） */
  listAddresses: async (): Promise<Address[]> => {
    const res = await api.get<{ addresses: Address[] }>('/v1/user/addresses');
    return res.addresses ?? [];
  },
  /** POST /api/v1/user/addresses（首条地址自动设默认） */
  createAddress: (p: AddressPayload) => api.post<{ addressId: number }>('/v1/user/addresses', p),
  updateAddress: (id: number, p: AddressPayload) => api.put<{ updated: boolean }>(`/v1/user/addresses/${id}`, p),
  removeAddress: (id: number) => api.del<{ deleted: boolean }>(`/v1/user/addresses/${id}`),
  setDefaultAddress: (id: number) => api.post<{ success: boolean }>(`/v1/user/addresses/${id}/default`),
};
