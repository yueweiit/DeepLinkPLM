import request, { unwrapResponse } from '../request'
import type { MarketWatchConfig, MarketWatchKind, MarketWatchSummary, MarketPhoneQuery, MarketPhonePage, MarketPhoneModel } from '@/types/marketWatch'

const root = '/market-watch'

export async function getMarketWatchConfig(): Promise<MarketWatchConfig> { return unwrapResponse(await request.get(`${root}/config`)) }
export async function getMarketWatchSummary(query?:MarketPhoneQuery): Promise<MarketWatchSummary> { return unwrapResponse(await request.get(`${root}/summary`,{params:query})) }
export async function getMarketWatchItems(query: MarketPhoneQuery): Promise<MarketPhonePage> { return unwrapResponse(await request.get(`${root}/items`, { params: query })) }
export async function getMarketWatchModel(rowKey:string): Promise<MarketPhoneModel> { return unwrapResponse(await request.get(`${root}/items/${encodeURIComponent(rowKey)}`)) }
export async function refreshMarketWatchProjection(): Promise<import('@/types/marketWatch').MarketPhoneSync> { return unwrapResponse(await request.post(`${root}/refresh`)) }
export async function reconcileMarketWatchPhones():Promise<{changedSources:number;matchedSources:number;unmatchedSources:number;ambiguousSources:number}> {return unwrapResponse(await request.post(`${root}/reconcile`))}
export async function linkMarketWatchPhoneModel(kind:MarketWatchKind,id:number,input:{phoneModelId:number;originalPhoneModelId:number|null;reason?:string}): Promise<import('@/types/marketWatch').MarketPhoneSource> { return unwrapResponse(await request.patch(`${root}/items/${kind}/${id}/phone-model`,input)) }

export async function updatePhoneMoldPlan(id:number,input:{status:string;originalStatus:string;reason:string}):Promise<MarketPhoneModel>{return unwrapResponse(await request.patch(`${root}/phones/${id}/mold-plan`,input))}

export async function refreshMarketPrices(start:boolean):Promise<{refreshing:boolean;updated:number;error:string|null}>{return unwrapResponse(await request({url:root+'/prices/refresh',method:start?'POST':'GET'}))}
