import request,{unwrapPage,unwrapResponse,type PageResponse} from '../request'
import type { CompatiblePhone,PhoneBrand,ReferencePatch,ReferenceQuery } from '@/types/molds'
const root='/molds/reference-data'
export async function getPhoneBrands(query:ReferenceQuery):Promise<PageResponse<PhoneBrand>> {return unwrapPage(await request.get(`${root}/brands`,{params:query}))}
export async function getAllPhoneBrands():Promise<PhoneBrand[]> {
  const brands:PhoneBrand[]=[]
  for(let page=1;;page++){const result=await getPhoneBrands({page,size:100});brands.push(...result.content);if(page>=result.totalPages || !result.content.length)return brands}
}
export async function getPhoneModels(query:ReferenceQuery):Promise<PageResponse<CompatiblePhone>> {return unwrapPage(await request.get(`${root}/phone-models`,{params:query}))}
export async function getPhoneSelectionBrands():Promise<PhoneBrand[]> {return unwrapResponse(await request.get(`${root}/phone-models/brands`))}
export async function createPhoneBrand(name:string,aliases:string[]=[],prefixes:PhoneBrand['prefixes']=[]):Promise<PhoneBrand> {return unwrapResponse(await request.post(`${root}/brands`,{name,aliases,prefixes}))}
export async function savePhoneBrand(id:number,patch:ReferencePatch):Promise<{status:string;row?:PhoneBrand;conflicts?:unknown[]}> {return unwrapResponse(await request.patch(`${root}/brands/${id}`,patch))}
export async function createPhoneModel(input:{brandId:number;modelName:string;aliases?:string[];codeItemId?:number|null}):Promise<CompatiblePhone> {return unwrapResponse(await request.post(`${root}/phone-models`,input))}
export async function savePhoneModel(id:number,patch:ReferencePatch):Promise<{status:string;row?:CompatiblePhone;conflicts?:unknown[]}> {return unwrapResponse(await request.patch(`${root}/phone-models/${id}`,patch))}
export async function getPhoneModelOptions(ids:number[]):Promise<CompatiblePhone[]> {return unwrapResponse(await request.get(`${root}/phone-models/options`,{params:{ids:ids.join(',')}}))}
export interface PhoneCompatibleMold {itemId:number;moldSetId:number;phoneModelId:number;modelName:string;codeItemId:number;phoneModelCode:string;moldName:string|null;styleName:string;engravedCode:string|null;physicalCode:string;setNo:number;parts:{partType:string;code:string}[];compatibilityDigest:string;pcConfirmed:boolean;tpuConfirmed:boolean;evidence:string}
export async function getPhoneCompatibleMolds(id:number,productLineId:number):Promise<PhoneCompatibleMold[]> {return unwrapResponse(await request.get(`${root}/phone-models/${id}/molds`,{params:{productLineId}}))}

export async function getPhoneModelCoverage(id:number):Promise<import('@/types/marketWatch').MarketPhoneCoverage[]> {return unwrapResponse(await request.get(`${root}/phone-models/${id}/coverage`))}

export type PhoneIssueState='pending'|'ready'|'resolved'|'not_phone'
export interface PhoneIdentityIssue {
  key:string;category:string;title:string;sourceDigest:string;state:PhoneIssueState;canResolve:boolean;
  phoneCodes:string[];modelNames:string[];phoneModelIds?:number[];brandIds?:number[];codeItemId?:number;focusPhoneModelId?:number;
  productNames?:string[];referenceCount?:number;missingFields?:string[];reason:string;evidence?:string[];
  autoAction?:string;suggestedName?:string;suggestedBrandIds?:number[];editTab?:string;
  historyKind?:'code_many_names'|'name_many_codes';historyKey?:string;reviewedAt?:string;reviewedBy?:string;reviewReason?:string
}
export interface PhoneIssuesPage {
  content:PhoneIdentityIssue[];page:number;size:number;totalElements:number;totalPages:number;
  summary:Record<PhoneIssueState|'needsAction',number>;readyItems:{key:string;sourceDigest:string}[];canMaintain:boolean
}
export async function getPhoneIdentityIssues(query:{page:number;size:number;keyword?:string;state?:PhoneIssueState|'all'}):Promise<PhoneIssuesPage>{return unwrapResponse(await request.get(`${root}/phone-models/issues`,{params:query}))}
export async function resolvePhoneIdentityIssues(items:{key:string;sourceDigest:string}[],reason:string):Promise<unknown>{return unwrapResponse(await request.post(`${root}/phone-models/issues/resolve`,{items,reason}))}
export async function editPhoneIdentityIssue(input:{codeItemId:number;sourceDigest:string;codeName:string;brandIds:number[];reason:string}):Promise<unknown>{return unwrapResponse(await request.post(`${root}/phone-models/issues/edit-code`,input))}
export interface PhoneBrandMergePlan {source:PhoneBrand;target:PhoneBrand;sourceDigest:string;phoneModelIds:number[];retiredDuplicatePhoneModelIds:number[];phoneCodes:string[];items:{item_id:number;brand_id:number}[];blockers:string[]}
export async function previewPhoneBrandMerge(sourceBrandId:number,targetBrandId:number):Promise<PhoneBrandMergePlan>{return unwrapResponse(await request.post(`${root}/brands/merge-preview`,{sourceBrandId,targetBrandId}))}
export async function mergePhoneBrands(input:{sourceBrandId:number;targetBrandId:number;sourceDigest:string;reason:string}):Promise<unknown>{return unwrapResponse(await request.post(`${root}/brands/merge`,input))}

export async function inspectPhoneReference(kind:'brands'|'phone-models',id:number|string):Promise<import('@/types/masterDataLifecycle').MasterDataInspection>{return unwrapResponse(await request.get(`${root}/${kind}/${id}/references`))}
export async function phoneReferenceAction(kind:'brands'|'phone-models',id:number|string,action:'delete'|'deactivate'|'activate',payload:{version:number;requestId:string;reason:string}):Promise<unknown>{return unwrapResponse(await request.post(`${root}/${kind}/${id}/actions/${action}`,payload))}
