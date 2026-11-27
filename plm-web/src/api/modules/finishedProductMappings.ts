import request,{unwrapResponse,type PageResponse} from '../request'

export interface FinishedProductMappingSku {
  productId:number;productName:string;phoneModelId:number;phoneModelCode:string;phoneModelName:string;finishedProductCode:string
}
export interface FinishedProductMappingMoldBasis {
  phoneModelId:number;phoneModelName:string;materialCode:string;moldMappingCode:string;primaryCode:string;moldSetId:number
}
export interface FinishedProductMapping {
  id:number;productLineId:number;productLineName:string;productSpecificCode:string;operationCode:string;holeType:'large'|'precision'|'mixed'
  referencePhoneModelId:number;referencePhoneName:string;referencePhoneCode:string;colorCode:string;mappingCode:string;status:'active'|'inactive'
  version:number;evidence:string;updatedAt:string;skus:FinishedProductMappingSku[];moldMappings:FinishedProductMappingMoldBasis[]
}
export async function getFinishedProductMappings(query:{page:number;size:number;status?:string;keyword?:string;productLineId?:number;holeType?:string}):Promise<PageResponse<FinishedProductMapping>&{canWrite:boolean}>{
  return unwrapResponse(await request.get('/finished-product-mappings',{params:query}))
}
