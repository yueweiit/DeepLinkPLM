import request,{unwrapResponse} from '../request'
import type {PhoneBrandReviewInput,PhoneBrandReviewPreview,PhoneBrandReviewResult,ProductionMetadataItem,ProductionMetadataResult,ProductionStatus} from '@/types/production'
export async function getProductionMetadataOptions():Promise<{canMaintain:boolean}>{return unwrapResponse(await request.get('/production-metadata/options'))}
export async function getProductProductionStatus(id:number):Promise<ProductionStatus>{const row=unwrapResponse<{productionStatus?:ProductionStatus}>(await request.get(`/products/${id}`));return row.productionStatus||'unknown'}
export async function saveProductionMetadata(kind:'phone'|'product',items:ProductionMetadataItem[],reason:string):Promise<ProductionMetadataResult[]>{
  const root=kind==='phone'?'/code-items':'/products',suffix=kind==='phone'?'phone-metadata':'production-status'
  if(items.length===1){const item=items[0]!;return [unwrapResponse(await request.patch(`${root}/${item.id}/${suffix}`,{changes:item.changes,original:item.original,reason}))]}
  return unwrapResponse(await request.post(`${root}/${suffix}/batch`,{items,reason}))
}
export async function getPhoneBrandReview():Promise<PhoneBrandReviewPreview>{return unwrapResponse(await request.get('/code-items/phone-metadata/brand-review'))}
export async function applyPhoneBrandReview(items:PhoneBrandReviewInput[],reason:string):Promise<PhoneBrandReviewResult>{return unwrapResponse(await request.post('/code-items/phone-metadata/brand-review/apply',{items,reason}))}

export interface SalesProduct {id:number;productName:string;productType:string;productionStatus:ProductionStatus;salesStatus:'on_sale'|'off_sale'|'unconfirmed';applicationAliases:string[]}
export async function getSalesCatalog():Promise<SalesProduct[]>{return unwrapResponse(await request.get('/products/sales-catalog'))}
export async function saveSalesProduct(row:SalesProduct,changes:Pick<SalesProduct,'salesStatus'|'applicationAliases'>,reason:string):Promise<SalesProduct>{return unwrapResponse(await request.patch(`/products/${row.id}/production-status`,{changes,original:{salesStatus:row.salesStatus,applicationAliases:row.applicationAliases},reason}))}
