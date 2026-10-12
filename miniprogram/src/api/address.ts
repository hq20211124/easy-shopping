import { http } from './client'
import type { Address, AddressPayload } from './types'

export const addressApi = {
  /** GET /api/v1/user/addresses（默认地址排首） */
  list: () => http.get<{ addresses: Address[] }>('/api/v1/user/addresses'),
  /** POST /api/v1/user/addresses（首条自动设默认） */
  create: (payload: AddressPayload) => http.post<{ addressId: number }>('/api/v1/user/addresses', payload),
  /** PUT /api/v1/user/addresses/{id} */
  update: (id: number, payload: AddressPayload) => http.put<{ updated: boolean }>(`/api/v1/user/addresses/${id}`, payload),
  /** DELETE /api/v1/user/addresses/{id}（软删；默认位自动顺延） */
  remove: (id: number) => http.del<{ deleted: boolean }>(`/api/v1/user/addresses/${id}`),
  /** POST /api/v1/user/addresses/{id}/default（设为默认） */
  setDefault: (id: number) => http.post<{ success: boolean }>(`/api/v1/user/addresses/${id}/default`, {}),
}
