import request, { unwrapResponse } from '../request'
import type { CatalogAddition, CatalogAdditionPreview, CatalogAdditionResult, CatalogCodeOption, CatalogIdentity, CatalogIdentityChoice, CatalogIdentityPreview, CatalogModelSheetInput, CatalogModelSheetPreview, CatalogModelSheetResult, CatalogPage, CatalogPurposePreview, CatalogSource, CatalogStyle, CatalogSupplier } from '@/types/partnerCatalog'
import type {SelectionRow} from '@/types/partnerSelections'
import type { AttachmentVO } from './attachment'

const root = '/partner-catalog'
export const catalogSuppliers = async (params: { q?: string; page: number; size: number; deleted?: string }) =>
  unwrapResponse<CatalogPage<CatalogSupplier>>(await request.get(`${root}/suppliers`, { params }))

export const catalogStyles = async (params: { supplierCode: string; keyword?: string; phoneModelId?: number; page: number; size: number; deleted?: string }) =>
  unwrapResponse<CatalogPage<CatalogStyle>>(await request.get(`${root}/styles`, { params }))

export const catalogStyle = async (supplierCode: string, identity: Omit<CatalogIdentity, 'rowVersion' | 'recordVersion'>,deleted=false) =>
  unwrapResponse<CatalogStyle>(await request.get(`${root}/styles/${identity.sourceKind}/${identity.recordId}/${encodeURIComponent(identity.rowId)}`, { params: { supplierCode,...(deleted?{deleted:'true'}:{}) } }))

export const updateCatalogStyle = async (supplierCode:string,identity:Omit<CatalogIdentity,'rowVersion'|'recordVersion'>,data:{changes:Partial<SelectionRow>;rowVersion:number;recordVersion:number;requestId:string}) =>
  unwrapResponse<{name:string;rowVersion:number;recordVersion:number;changed:boolean}>(await request.patch(`${root}/styles/${identity.sourceKind}/${identity.recordId}/${encodeURIComponent(identity.rowId)}`,data,{params:{supplierCode}}))

export const addCatalogSelection = async (data: CatalogAddition) =>
  unwrapResponse<CatalogAdditionResult>(await request.post(`${root}/selection-additions`, data))

export const previewCatalogSelection = async (data: Omit<CatalogAddition, 'requestId' | 'previewToken'>) =>
  unwrapResponse<CatalogAdditionPreview>(await request.post(`${root}/selection-additions/preview`, data))

export const catalogSources = async (params: { supplierCode?: string; page: number; size: number; purpose?: 'historical' | 'catalog_source' }) =>
  unwrapResponse<CatalogPage<CatalogSource>>(await request.get(`${root}/sources`, { params }))

export const createCatalogSource = async (data: { name: string; supplierCode: string; requestId: string }) =>
  unwrapResponse<CatalogSource>(await request.post(`${root}/sources`, data))

export const catalogSourceDetail = async (id: number) =>
  unwrapResponse<CatalogSource>(await request.get(`${root}/sources/${id}`))

export const previewCatalogSourcePurpose = async (id: number, data: { version: number; purpose: 'catalog_source'; reason: string }) =>
  unwrapResponse<CatalogPurposePreview>(await request.post(`${root}/sources/${id}/purpose-preview`, data))

export const changeCatalogSourcePurpose = async (id: number, data: { version: number; purpose: 'catalog_source'; reason: string; requestId: string; previewToken: string }) =>
  unwrapResponse<CatalogSource>(await request.post(`${root}/sources/${id}/purpose`, data))
export const catalogStyleIdentities=async(params:{supplierCode:string;keyword?:string;page:number;size:number})=>unwrapResponse<CatalogPage<CatalogCodeOption>>(await request.get(`${root}/style-identities`,{params}))
export const previewCatalogIdentities=async(data:{supplierCode:string;items:CatalogIdentityChoice[]})=>unwrapResponse<CatalogIdentityPreview>(await request.post(`${root}/styles/identity-preview`,data))
export const saveCatalogIdentities=async(data:{supplierCode:string;items:CatalogIdentityChoice[];reason:string;requestId:string;previewToken:string})=>unwrapResponse<{items:CatalogCodeOption[];sourceRowsUnchanged:boolean}>(await request.post(`${root}/styles/identities`,data))
export const catalogSourceRows=async(id:number,params:{supplierCode:string;keyword?:string;page:number;size:number})=>unwrapResponse<CatalogPage<CatalogStyle>>(await request.get(`${root}/styles`,{params:{...params,sourceRecordId:id,sourceKind:'quote'}}))
export const patchCatalogSourceRows=async(id:number,data:{version:number;requestId:string;operations:{rowId:string;rowVersion:number;changes:Partial<SelectionRow>}[]})=>unwrapResponse<{id:number;version:number;lineCount?:number}>(await request.patch(`/partner-operations/supplier_quote/${id}/lines`,data))

export const previewCatalogModelSheet = async (data: CatalogModelSheetInput) =>
  unwrapResponse<CatalogModelSheetPreview>(await request.post(`${root}/styles/model-sheet-preview`, data))
export const saveCatalogModelSheet = async (data: CatalogModelSheetInput & { requestId: string; previewToken: string }) =>
  unwrapResponse<CatalogModelSheetResult>(await request.post(`${root}/styles/model-sheets`, data))
export const uploadCatalogModelSheetFile = async (identity: CatalogIdentity & { supplierCode: string; requestId: string; sha256: string }, file: File) => {
  const data = new FormData()
  for (const [key, value] of Object.entries(identity)) data.append(key, String(value))
  data.append('file', file)
  return unwrapResponse<AttachmentVO>(await request.post(`${root}/styles/model-sheet-file`, data))
}
