import request,{unwrapResponse,authorizedPreviewOptions} from '../request'
import type {SampleCandidates,SampleIdentity,SamplePage,SampleResolution,SampleShipment,SampleShipmentInput,SampleLocationOption,SampleLocationInputData,SampleLocationMoveResult,SampleStyleGroup,SampleSupplierOption,SampleLocationReference} from '@/types/sampleManagement'
export const sampleIdentities=async(params:Record<string,unknown>={})=>unwrapResponse<SamplePage<SampleIdentity>>(await request.get('/sample-identities',{params}))
export interface SampleIdentityQueryInput {filters?:Record<string,unknown>;groupKeys?:string[];ids?:number[];details?:boolean}
export const querySampleIdentities=async(data:SampleIdentityQueryInput)=>unwrapResponse<{content:SampleIdentity[];totalElements:number}>(await request.post('/sample-identities/query',data,{suppressErrorToast:true}))
export const sampleIdentity=async(id:number)=>unwrapResponse<SampleIdentity>(await request.get(`/sample-identities/${id}`))
export const sampleIdentityCandidates=async(params:Record<string,unknown>)=>unwrapResponse<SampleCandidates>(await request.get('/sample-identities/candidates',{params}))
export const resolveSampleIdentity=async(code:string)=>unwrapResponse<SampleResolution>(await request.get('/sample-identities/resolve',{params:{code}}))
export const createSampleIdentity=async(data:Record<string,unknown>)=>unwrapResponse<SampleIdentity>(await request.post('/sample-identities',data))
export const sampleIdentityAction=async(id:number,action:string,data:Record<string,unknown>)=>unwrapResponse<SampleIdentity>(await request.post(`/sample-identities/${id}/actions/${action}`,data))
export const sampleShipments=async(params:Record<string,unknown>={})=>unwrapResponse<SamplePage<SampleShipment>>(await request.get('/sample-shipments',{params}))
export const sampleShipment=async(id:number)=>unwrapResponse<SampleShipment>(await request.get(`/sample-shipments/${id}`))
export const saveSampleShipment=async(data:SampleShipmentInput,id?:number)=>unwrapResponse<SampleShipment>(await request({url:'/sample-shipments'+(id?`/${id}`:''),method:id?'PUT':'POST',data}))
export const deleteSampleShipment=async(id:number,data:{version:number;requestId:string;reason:string})=>unwrapResponse(await request.delete(`/sample-shipments/${id}`,{data}))
export const sampleShipmentAction=async(id:number,action:string,data:Record<string,unknown>)=>unwrapResponse<SampleShipment>(await request.post(`/sample-shipments/${id}/actions/${action}`,data))
export const scanSample=async(code:string)=>unwrapResponse<SampleResolution>(await request.get('/sample-scan',{params:{code}}))

export const sampleShipmentOptions=async()=>unwrapResponse<{receivers:{id:number;name:string}[]}>(await request.get('/sample-shipments/options'))

export const sampleIdentityBulkAction=async(action:'loan'|'return'|'move'|'void_receipt',data:Record<string,unknown>)=>unwrapResponse<{content:SampleIdentity[]}>(await request.post(`/sample-identities/bulk-actions/${action}`,data))
export interface SampleColorChoice {id:number;name:string;supplierColorCode?:string}
export interface SampleColorPreview {sampleCode:string;version:number;before:{id:number;name:string};after:{id:number;name:string};targetIdentityId:number;targetSampleCode:string;batchCount:number}
export const sampleIdentityColors=async(id:number)=>unwrapResponse<{content:SampleColorChoice[]}>(await request.get(`/sample-identities/${id}/colors`))
export const previewSampleColor=async(id:number,data:Record<string,unknown>)=>unwrapResponse<SampleColorPreview>(await request.post(`/sample-identities/${id}/color-correction-preview`,data))

export const sampleLocations=async(params:Record<string,unknown>={})=>unwrapResponse<SamplePage<SampleLocationOption>&{canWrite?:boolean}>(await request.get('/sample-locations',{params}))
export const sampleLocationOptions=async(params:Record<string,unknown>={})=>unwrapResponse<{content:SampleLocationOption[];hasMore?:boolean;size?:number}>(await request.get('/sample-locations/options',{params}))
export const saveSampleLocation=async(data:SampleLocationInputData,id?:number)=>unwrapResponse<SampleLocationOption>(await request({url:'/sample-locations'+(id?`/${id}`:''),method:id?'PUT':'POST',data}))
export const deleteSampleLocation=async(id:number,data:{requestId:string;version:number})=>unwrapResponse<SampleLocationOption>(await request.delete(`/sample-locations/${id}`,{data}))
export const setSampleLocations=async(data:Record<string,unknown>)=>unwrapResponse<SampleLocationMoveResult>(await request.post('/sample-identities/bulk-actions/move',data))

export const sampleStyleGroups=async(params:Record<string,unknown>={})=>unwrapResponse<SamplePage<SampleStyleGroup>>(await request.get('/sample-identities',{params:{...params,groupBy:'style'}}))
export const sampleIdentityOptions=async()=>unwrapResponse<{suppliers:SampleSupplierOption[]}>(await request.get('/sample-identities/options'))
export const sampleLocationReferences=async(id:number)=>unwrapResponse<{id:number;referenced:boolean;referenceCount:number;onhand:number;events:number;records:number;batches:number;references:SampleLocationReference[]}>(await request.get(`/sample-locations/${id}/references`))
export const sampleIdentityPhoto=async(id:number,fileId:number,options?:{signal?:AbortSignal})=>(await request.get<Blob>(`/sample-identities/${id}/photos/${fileId}/preview`,authorizedPreviewOptions(options))).data
