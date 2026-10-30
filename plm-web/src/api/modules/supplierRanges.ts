import request,{unwrapResponse} from '../request'
import type {SupplierRangeReference} from '@/types/partnerSelections'
export interface SupplierScopeMetadata {supplierCode:string;supplierName:string;shortCode:string;version:number}
export interface SupplierColorReference {id:number;name?:string;version?:string;type?:string;contentType?:string;size?:number}
export interface SupplierColor {id:number;code:string;name:string;supplierColorCode:string;styleKey?:string;configKey?:string;standardColorId?:number;refs:SupplierColorReference[];status:'active'|'inactive';version:number;notes?:string}
export interface SupplierRangeMember {id:number;raw?:string;name?:string;modelName?:string;code?:string;[key:string]:unknown}
export interface SupplierRange {id:number;code:string;type:'model'|'color';name:string;aliases:string[];styleKey?:string;configKey?:string;status:'draft'|'active'|'inactive';version:number;members:SupplierRangeMember[];pendingValues?:unknown[];notes?:string}
export interface SupplierScopePage<T> {content:T[];totalElements:number;page:number;size:number;supplier:SupplierScopeMetadata;canWrite:boolean;commonColors?:string[]}
export interface SupplierRangePreview {rows:{raw:string;state:'matched'|'duplicate'|'ambiguous'|'missing';candidates:SupplierRangeMember[];member?:SupplierRangeMember}[];members:SupplierRangeMember[];unresolvedCount:number}
const root=(code:string)=>`/inventories/suppliers/${encodeURIComponent(code)}`
export const supplierColors=async(code:string,params:Record<string,unknown>={})=>unwrapResponse<SupplierScopePage<SupplierColor>>(await request.get(`${root(code)}/colors`,{params}))
export const supplierRanges=async(code:string,params:Record<string,unknown>={})=>unwrapResponse<SupplierScopePage<SupplierRange>>(await request.get(`${root(code)}/ranges`,{params}))
export const saveSupplierColors=async(code:string,data:{requestId:string;reason:string;items:Partial<SupplierColor>[];reuseExisting?:boolean})=>unwrapResponse<{content:SupplierColor[];supplier:SupplierScopeMetadata}>(await request.post(`${root(code)}/colors/batch`,{...data,items:data.items.map(item=>({...item,...(item.refs?{refs:item.refs.map(ref=>({id:ref.id}))}:{})}))}))
export const previewSupplierRange=async(code:string,data:{type:'model'|'color';values:string[];memberIds?:number[];styleKey?:string;configKey?:string})=>unwrapResponse<SupplierRangePreview>(await request.post(`${root(code)}/ranges/preview`,data))
export const saveSupplierRanges=async(code:string,data:{requestId:string;reason:string;items:Partial<SupplierRange>[]})=>unwrapResponse<{content:SupplierRange[];supplier:SupplierScopeMetadata}>(await request.post(`${root(code)}/ranges/batch`,data))
export const resolveSupplierRanges=async(code:string,references:SupplierRangeReference[],styleKey?:string,configKey?:string)=>unwrapResponse<{snapshots:Record<string,unknown>[];errors:unknown[]}>(await request.post(`${root(code)}/ranges/resolve`,{references,styleKey,configKey}))
export const updateSupplierShortCode=async(code:string,data:{version:number;shortCode:string;requestId:string;reason:string})=>unwrapResponse<SupplierScopeMetadata>(await request.patch(`${root(code)}/metadata`,data))
