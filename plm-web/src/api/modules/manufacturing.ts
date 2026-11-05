import request,{unwrapResponse,type PageResponse} from '@/api/request'
import type {CompatiblePhone} from '@/types/molds'
import type {HoleType} from './moldApplications'
export interface ManufacturingRelation {
 physicalCode?:string;setNo?:number;
 id:number;relationId:number;version:number;relationVersion:number;productLineId:number;itemId:number;moldSetId:number;phoneModelId:number;holeType:HoleType;structureDescription:string;sharedMaterial:boolean;manufacturingProductId:number|null;manufacturingPhoneCode:string|null;manufacturingProductName?:string;manufacturingProductCode?:string;skus?:ManufacturingObject[];skuIds:number[];moldEvidence:string;materialEvidence:string;compatibilityDigest:string;reason:string;status:'active'|'inactive';businessPhone:CompatiblePhone;styleName?:string;moldName?:string;parts?:{partType:string;code:string}[];canWrite?:boolean
}
export type ManufacturingRelationInput=Pick<ManufacturingRelation,'productLineId'|'itemId'|'moldSetId'|'phoneModelId'|'holeType'|'structureDescription'|'sharedMaterial'|'manufacturingProductId'|'skuIds'|'moldEvidence'|'materialEvidence'|'compatibilityDigest'|'reason'|'status'>&{version?:number}
export interface ManufacturingObject {id:number;code:string;name:string;phoneModelCode?:string;colorCode?:string;operationCode?:string}
export interface ManufacturingResolution extends Partial<Omit<ManufacturingRelation,'status'>> {status:'reuse'|'pending';reason?:string;reasons?:string[]}
export async function getManufacturingRelations(query:{productLineId?:number;phoneModelId?:number;itemId?:number;moldSetId?:number;status?:string;keyword?:string;page:number;size:number}):Promise<PageResponse<ManufacturingRelation>>{return unwrapResponse(await request.get('/manufacturing-relations',{params:query}))}
export async function getManufacturingRelation(id:number):Promise<ManufacturingRelation>{return unwrapResponse(await request.get(`/manufacturing-relations/${id}`))}
export async function saveManufacturingRelation(id:number|null,input:ManufacturingRelationInput):Promise<ManufacturingRelation>{return unwrapResponse(id?await request.put(`/manufacturing-relations/${id}`,input):await request.post('/manufacturing-relations',input))}
export async function getManufacturingOptions(query:{kind:'products'|'skus';productLineId:number;manufacturingProductId?:number;keyword?:string;page:number;size:number}):Promise<PageResponse<ManufacturingObject>>{return unwrapResponse(await request.get('/manufacturing-relations/options',{params:query}))}
export async function resolveManufacturing(input:{productLineId:number;phoneModelId:number;holeType:HoleType;structureDescription:string;relationId?:number;expectedVersion?:number}):Promise<ManufacturingResolution>{return unwrapResponse(await request.post('/manufacturing-relations/resolve',input))}
