export type ProductionStatus = 'unknown'|'active'|'discontinued'
export interface ProductionMetadataTarget {id:number;label:string;productionStatus?:ProductionStatus;brandIds?:number[];memberBrandIds?:number[]}
export interface ProductionMetadataItem {id:number;changes:Record<string,unknown>;original:Record<string,unknown>}
export interface ProductionMetadataResult {id:number;productionStatus:ProductionStatus;brandIds?:number[];memberBrandIds?:number[]}
export interface PhoneBrandReviewItem {id:number;codeValue:string;codeName:string;originalBrandIds:number[];brandIds:number[];brandNames:string[];state:'ready'|'unresolved';reason:string;evidence:string[]}
export interface PhoneBrandReviewPreview {total:number;candidateCount:number;unresolvedCount:number;items:PhoneBrandReviewItem[]}
export type PhoneBrandReviewInput=Pick<PhoneBrandReviewItem,'id'|'codeValue'|'codeName'|'originalBrandIds'|'brandIds'>
export interface PhoneBrandReviewResult {appliedCount:number;items:ProductionMetadataResult[]}
