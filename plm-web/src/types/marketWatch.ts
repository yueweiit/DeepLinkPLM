export type MarketWatchKind = 'phone' | 'candidate'

export interface MarketWatchLink {
  sourceKind: MarketWatchKind
  sourceId: number
  itemId: number
  promotedById: number
  promotedByName: string
  promotedAt: string
}

export interface MarketWatchItem {
  [key: string]: unknown
  id: number
  kind: MarketWatchKind
  brand: string
  country?: string
  source_country?: string
  missing_country?: string
  model: string
  normalized_model: string
  image_url?: string
  price?: string
  priceAmount?: number | null
  priceCurrency?: string | null
  badge?: string
  sale_status?: string
  sampling_status: string
  source_name?: string
  source_url?: string
  reason?: string
  first_seen_at: string
  last_seen_at: string
  collaborationLink?: MarketWatchLink | null
}

export interface MarketWatchConfig { enabled: boolean; writable: boolean; administrator: boolean }
export type MoldOpportunityView = 'opportunities'|'to_apply'|'applied'|'making'|'partially_molded'|'molded'|'not_planned'|'all'|'needs_confirmation'|'applying'
export type MarketPhoneMatchState = 'matched' | 'unmatched' | 'ambiguous'
export type MarketPhoneCoverageState = 'uncovered' | 'catalogued' | 'draft' | 'applying' | 'planned' | 'making' | 'partial' | 'confirmed' | 'reusable' | 'paused' | 'terminated' | 'needs_review'
export interface MarketPhoneSource extends MarketWatchItem { active: boolean; phoneModelId: number | null; matchState: MarketPhoneMatchState; matchOrigin: 'automatic' | 'manual'; candidatePhones: import('./molds').CompatiblePhone[] }
export interface MarketPhoneApplication { applicationId:number; productLineId:number|null; tipo:string|null; model:string|null; status:string; businessStatus:string; coverageState?:MarketPhoneCoverageState; approvalGranted:boolean; executionIntent:'new'|'new_set'|'reuse'; executionStatus:'pending'|'active'|'paused'|'terminated'; provisioningStatus:'not_requested'|'pending'|'ready'|'blocked'; provisioningError:string|null; projectId:number|null; itemId:number|null; moldSetId:number|null; updatedAt:string }
export interface MarketReviewReason {code:string;message:string;action:string;field?:string}
export interface MarketPhoneMold { itemId:number; moldSetId:number|null; moldName:string|null; styleName:string; setNo:number|null; physicalCode:string|null; engravedCode:string|null; projectId:number|null; pcCode:string|null; tpuCode:string|null; pcState:string; tpuState:string; state:MarketPhoneCoverageState; reusable:boolean; compatibilityConfirmed:boolean; pcConfirmed:boolean; tpuConfirmed:boolean; evidence:string|null; pcEvidence?:string|null; tpuEvidence?:string|null; pcLocation?:string|null; tpuLocation?:string|null; pcLocationDetail?:string|null; tpuLocationDetail?:string|null; plannedDeliveryDate:string|null;reviewReasons?:MarketReviewReason[];hasRegisteredSets?:boolean;registeredSets?:number }
export interface MarketPhoneProduct {productId:number;productCode:string;productName:string;productType:'model_variant'|'sku';phoneModelCode:string|null;model:string;status:string;productionStatus:string;identitySource:'member'|'parent'|'code';color:string|null}
export interface MarketPhoneCoverage { productLineId:number|null; productLineName:string; state:MarketPhoneCoverageState; states:MarketPhoneCoverageState[]; applications:MarketPhoneApplication[]; molds:MarketPhoneMold[];products?:MarketPhoneProduct[] }
export interface MarketPhoneModel {
  moldStatus?:string;wholePhoneStatus?:string;coverageTotal?:number;openedProductCount?:number;makingProductCount?:number;appliedProductCount?:number;gapProductCount?:number;missingInformationCount?:number;applicationEvidence?:string;hasActiveExecution?:boolean;moldPlanStatus?:string;moldPlanReason?:string;productCoverage?:{productLineId:number;productLineName:string;status:string;skuCount:number;applicationCount:number;reusable:boolean}[];preferredPrice?:MarketVerifiedPrice|null;mexicoPrice?:MarketVerifiedPrice|null;prices?:MarketVerifiedPrice[];historicalPrices?:{applicationId:number;price:string;currency:string|null;recordedAt:string|null}[];referenceLinks?:{url:string;source:string;kind:string;model?:string}[];priceErrors?:{source:string;message:string;checkedAt:string}[]; rowKey:string; phoneModelId:number|null; brandId:number|null; brandName:string; modelName:string; phoneModelCode:string|null; codeItemId:number|null; matchState:MarketPhoneMatchState; candidatePhones:import('./molds').CompatiblePhone[]; sources:MarketPhoneSource[]; sourceCount:number; countries:string[]; coverages:MarketPhoneCoverage[]; coverageStates:MarketPhoneCoverageState[]; applicationCount:number;productCount?:number;images?:string[]; lastSeenAt:string|null; opportunityViews?:MoldOpportunityView[]; opportunityReasons?:string[]; sourceSnapshot?:MarketPhoneSourceSnapshot }
export interface MarketPhoneSourceSnapshot { rowKey:string; phoneModelId:number; sources:MarketPhoneSource[]; capturedAt:string }
export interface MarketPhoneSync { lastSuccessAt:string|null; lastAttemptAt:string|null; lastError:string|null; refreshing:boolean; cached:boolean }
export interface MarketPhoneQuery { page:number; size:number; keyword?:string; brandId?:number; country?:string; productLineId?:number; coverageState?:MarketPhoneCoverageState; opportunityView?:MoldOpportunityView; matchState?:MarketPhoneMatchState }
export interface MarketPhoneStats { total:number; matched:number; unmatched:number; ambiguous:number; states:Partial<Record<MarketPhoneCoverageState,number>>; opportunities?:Partial<Record<MoldOpportunityView,number>> }
export interface MarketPhonePage { content:MarketPhoneModel[]; page:number; size:number; totalElements:number; totalPages:number; stats:MarketPhoneStats; sync:MarketPhoneSync; capabilities:{canMatch:boolean;canMaintainReference?:boolean} }

export interface MarketWatchSummary {stats:MarketPhoneStats;sync:MarketPhoneSync;capabilities:{canMatch:boolean;canMaintainReference?:boolean}}

export interface MarketVerifiedPrice {identityEvidence?:{reason:string;officialUrl:string;reviewedAt?:string;reviewedBy?:string};verified:boolean;amount:number;currency:string;specs:string;source:string;url:string;capturedAt:string;stale:boolean;error?:string|null;kind:string}
