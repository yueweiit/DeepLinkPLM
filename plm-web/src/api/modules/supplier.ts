import type { SupplierCenterSnapshot, SupplierDetail } from '@/types/supplier'

import request, { unwrapResponse } from '../request'

export interface SupplierSupplySidePayload {
  supplierType?: string
  version?: number
  requestId?: string
  changeReason?: string
  supplierCode?: string
  supplierName: string
  shortName: string
  contactPerson: string
  contactPhone: string
  contactEmail: string
  region: string
  supplyCategories: string[]
  paymentTerm: string
  cooperationLevel: string
  deliveryRisk: string
  status: 'draft' | 'active' | 'inactive'
}

export async function getSupplierCenterSnapshot(): Promise<SupplierCenterSnapshot> {
  return unwrapResponse<SupplierCenterSnapshot>(await request.get('/inventories/suppliers/snapshot'))
}

export async function createInventorySupplier(payload: SupplierSupplySidePayload): Promise<SupplierDetail> {
  return unwrapResponse<SupplierDetail>(await request.post('/inventories/suppliers', payload))
}

export async function updateInventorySupplier(
  supplierCode: string,
  payload: SupplierSupplySidePayload
): Promise<SupplierDetail> {
  return unwrapResponse<SupplierDetail>(
    await request.put(`/inventories/suppliers/${encodeURIComponent(supplierCode)}`, payload)
  )
}

export async function getSupplierPage(params: Record<string, unknown>) { return unwrapResponse<import('@/types/partner').PartnerPage>(await request.get('/inventories/suppliers/page',{params})) }
export async function getSupplierDetail(code:string) { return unwrapResponse<import('@/types/supplier').SupplierDetail>(await request.get('/inventories/suppliers/'+encodeURIComponent(code))) }

export type SupplierLifecycleAction = 'delete' | 'deactivate' | 'activate'
export interface SupplierReferences {
  supplierId: number
  supplierCode: string
  supplierName: string
  status: string
  version: number
  canDelete: boolean
  canDeactivate: boolean
  canActivate: boolean
  hasRestrictedReferences: boolean
  references: {kind: string; label: string; count: number; items: {id: number | string; label: string; targetPath?: string; history?: boolean; needsReview?: boolean; matchBasis?: string}[]}[]
}
export async function getSupplierReferences(code: string): Promise<SupplierReferences> {
  return unwrapResponse<SupplierReferences>(await request.get(`/inventories/suppliers/${encodeURIComponent(code)}/references`))
}
export async function supplierLifecycle(code: string, action: SupplierLifecycleAction, payload: {version: number; requestId: string; reason: string}) {
  return unwrapResponse<{supplierCode: string; deleted: boolean; status: string; version: number}>(
    await request.post(`/inventories/suppliers/${encodeURIComponent(code)}/actions/${action}`, payload)
  )
}
