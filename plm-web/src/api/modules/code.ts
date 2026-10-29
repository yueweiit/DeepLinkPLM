import request, { unwrapPage, unwrapResponse, type PageResponse } from '../request'

export interface CodeItem {
  codeItemId: number
  codeType: string
  codeValue: string
  codeName: string
  codeNameZh?: string
  status: 'enabled' | 'disabled'
  productionStatus?:import('@/types/production').ProductionStatus
  brands?:{brandId:number;name:string;status:string;memberConfirmed?:boolean}[]
  sortOrder: number
  version?:number
  updatedAt?: string
}

export interface CodeItemQuery {
  codeType?: string
  keyword?: string
  status?: string
  productionStatus?:import('@/types/production').ProductionStatus
  brandId?:number
  unclassified?:boolean
  page?: number
  size?: number
}

export interface CodeItemPayload {
  version?:number
  reason?:string
  codeType: string
  codeValue: string
  codeName: string
  codeNameZh?: string
  sortOrder: number
}

export type PhoneModelReviewKind = 'code_many_names' | 'name_many_codes'
export interface PhoneModelReviewRow {
  key: string
  codeValues: string[]
  modelNames: string[]
  sourceCount: number
  sourceDigest?: string
  reviewState?: 'pending' | 'confirmed'
  conclusion?: PhoneModelReviewConclusion
  reviewReason?: string
  reviewedAt?: string
  reviewedBy?: string
  reviewPhoneModelIds?: number[]
  reviewBlockReason?: string
  canReview?: boolean
  references?: {projectId:number;productType:string;model:string;phoneModelCode:string;phoneModelId:number|null;currentPhoneName?:string|null}[]
}
export type PhoneModelReviewConclusion='same_phone_alias'|'shared_code_distinct_phones'|'shared_mold_different_codes'|'combined_original_preserved'
export interface PhoneModelReviewConfirmation {kind:PhoneModelReviewKind;key:string;sourceDigest:string;conclusion:PhoneModelReviewConclusion;reason:string;phoneModelIds?:number[];projectLinks?:{projectId:number;originalPhoneModelId:number|null;phoneModelId:number}[]}
export async function getPhoneModelReview(params: { kind: PhoneModelReviewKind; page: number; size: number; keyword?: string;reviewState?:'pending'|'confirmed' }): Promise<PageResponse<PhoneModelReviewRow>> {
  return unwrapPage(await request.get('/code-items/phone-model-review', { params }))
}
export async function confirmPhoneModelReview(payload:PhoneModelReviewConfirmation):Promise<PhoneModelReviewRow> {
  return unwrapResponse(await request.post('/code-items/phone-model-review/confirm',payload))
}

export interface CodeImportRow {
  rowNo: number
  codeValue: string
  codeName: string
  codeNameZh?: string
  status: 'enabled' | 'disabled'
  sortOrder: number
  action: 'create' | 'update' | 'unchanged'
}

export interface CodeImportError {
  rowNo: number
  codeValue: string
  field: string
  reason: string
}

export interface CodeImportPreview {
  importToken: string
  createCount: number
  updateCount: number
  unchangedCount: number
  errorCount: number
  committedCount: number
  rows: CodeImportRow[]
  errors: CodeImportError[]
}

export async function getCodeItems(params: CodeItemQuery = {}): Promise<PageResponse<CodeItem>> {
  return unwrapPage<CodeItem>(await request.get('/code-items', { params }))
}

export async function getEnabledColorCodes(): Promise<CodeItem[]> {
  const rows:CodeItem[]=[]
  for(let page=1;;page++){const result=await getCodeItems({codeType:'color',status:'enabled',page,size:100});rows.push(...result.content);if(page>=result.totalPages||!result.content.length)return rows}
}

export async function createCodeItem(payload: CodeItemPayload): Promise<CodeItem> {
  return unwrapResponse<CodeItem>(await request.post('/code-items', payload))
}

export async function updateCodeItem(id: number, payload: CodeItemPayload): Promise<CodeItem> {
  return unwrapResponse<CodeItem>(await request.put(`/code-items/${id}`, payload))
}

export async function enableCodeItem(id: number): Promise<CodeItem> {
  return unwrapResponse<CodeItem>(await request.post(`/code-items/${id}/enable`))
}

export async function disableCodeItem(id: number): Promise<CodeItem> {
  return unwrapResponse<CodeItem>(await request.post(`/code-items/${id}/disable`))
}

export async function previewCodeImport(file: File): Promise<CodeImportPreview> {
  const form = new FormData(); form.append('file', file)
  return unwrapResponse<CodeImportPreview>(await request.post('/code-items/import/preview', form))
}

export async function commitCodeImport(token: string): Promise<CodeImportPreview> {
  return unwrapResponse<CodeImportPreview>(await request.post(`/code-items/import/${token}/commit`))
}

export async function inspectCodeItem(id:number|string):Promise<import('@/types/masterDataLifecycle').MasterDataInspection>{return unwrapResponse(await request.get(`/code-items/${id}/references`))}
export async function codeItemAction(id:number|string,action:'delete'|'deactivate'|'activate',payload:{version:number;requestId:string;reason:string}):Promise<unknown>{return unwrapResponse(await request.post(`/code-items/${id}/actions/${action}`,payload))}
