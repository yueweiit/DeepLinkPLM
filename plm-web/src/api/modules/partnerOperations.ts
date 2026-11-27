import request, { unwrapResponse } from '../request'
import type { BusinessData, PartnerPage, PartnerRow } from '@/types/partner'
import type { PhoneObjectPage, ObjectPhoneGroup } from '@/components/PhoneObjectSelect/types'
import type { CatalogListingOptions, CatalogListingRequest, ListedCatalogRow } from '@/types/partnerLifecycle'
import type { CrmListingCapabilities, CrmListingPushOptions, CrmListingPushItem, CrmListingSync } from '@/types/partnerLifecycle'

export type OperationKind = 'supplier_quote' | 'sample_fee' | 'inspection' | 'fulfilment' | 'listing'
export interface OperationCapabilities { engineering: boolean; costs: boolean; listing: boolean; delivery: boolean; externalListingConnected: boolean }
const root = '/partner-operations'
export async function operationCapabilities(): Promise<OperationCapabilities> { const { canReadCost, ...data } = unwrapResponse<Omit<OperationCapabilities, 'costs'> & { canReadCost: boolean }>(await request.get(root + '/capabilities')); return { ...data, costs: canReadCost } }
export const operationPage = async (kind: OperationKind, params: BusinessData = {}) => unwrapResponse<PartnerPage>(await request.get(`${root}/${kind}`, { params }))
export const operationDetail = async (kind: OperationKind, id: number) => unwrapResponse<PartnerRow>(await request.get(`${root}/${kind}/${id}`))
export const createOperation = async (kind: OperationKind, data: BusinessData) => unwrapResponse<PartnerRow>(await request.post(`${root}/${kind}`, data))
export const operationAction = async (kind: OperationKind, id: number, action: string, data: BusinessData) => unwrapResponse<PartnerRow>(await request.post(`${root}/${kind}/${id}/actions/${action}`, data))
export const confirmPartnerListing = async (id: number, data: BusinessData) => unwrapResponse<PartnerRow>(await request.post(`${root}/products/${id}/listing-confirmations`, data))
export const catalogListingOptions = async (selectionId:number,selectionRowId:string) => unwrapResponse<CatalogListingOptions>(await request.get(`${root}/catalog/listing-options`,{params:{selectionId,selectionRowId}}))
export const confirmCatalogListing = async (data:CatalogListingRequest) => unwrapResponse<PartnerRow>(await request.post(`${root}/catalog/listing-confirmations`,data))
export const listedCatalogPage = async (params:{status:'listed'|'pending'|'unlisted'|'all';keyword?:string;page:number;size:number}) => unwrapResponse<PartnerPage & {content:ListedCatalogRow[]}>(await request.get(`${root}/catalog/listings`,{params}))
export const operationTasks = async () => unwrapResponse<PartnerRow[]>(await request.get(root + '/tasks'))
const crmListingRoot = root + '/catalog/listings'
export const crmListingCapabilities = async () => unwrapResponse<CrmListingCapabilities>(await request.get(`${crmListingRoot}/crm-capabilities`))
export const crmListingPushOptions = async (id:number) => unwrapResponse<CrmListingPushOptions>(await request.get(`${crmListingRoot}/${id}/crm-push-options`))
export const pushCrmListings = async (requestId:string,items:CrmListingPushItem[]) => unwrapResponse<{taskIds:number[];status?:string;replayed?:boolean}>(await request.post(`${crmListingRoot}/crm-pushes`,{requestId,items}))
export const crmListingSync = async (id:number) => unwrapResponse<CrmListingSync>(await request.get(`${crmListingRoot}/${id}/crm-sync`))
export const retryCrmListing = async (id:number,taskId:number,requestId:string) => unwrapResponse<{taskIds:number[]}>(await request.post(`${crmListingRoot}/${id}/crm-retries`,{taskId,requestId}))
export const operationSpecOptions = async (params: BusinessData) => unwrapResponse<PartnerPage & PhoneObjectPage<BusinessData>>(await request.get(root + '/spec-options', { params }))
export const operationSpecPhones = async (params: BusinessData) => unwrapResponse<PhoneObjectPage<ObjectPhoneGroup> & { unlinkedCount: number }>(await request.get(root + '/spec-options/phones', { params }))
export async function uploadOperationFile(kind: OperationKind, id: number, file: File, purpose = 'material') {
  const data = new FormData(); data.append('file', file); data.append('purpose', purpose)
  return unwrapResponse<{ attachmentId: number; fileName: string; versionNo: string }>(await request.post(`${root}/${kind}/${id}/files`, data))
}
export function operationTarget(kind: OperationKind, id: number, from?: string) {
  return { path: `/partner-operations/${kind}/${id}`, query: from ? { from } : {} }
}
