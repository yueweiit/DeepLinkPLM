import request, { unwrapPage, unwrapResponse, type PageResponse } from '../request'
import type { CollaborationRow, PlanHistory, PlanQuery, RowPatch, RowResult } from '@/types/collaboration'
import type { MoldMaster,MoldMasterDetail,MoldMasterOptions,MoldMasterQuery } from '@/types/molds'
const root = '/molds'
export async function getMolds(query: PlanQuery): Promise<PageResponse<CollaborationRow>> { return unwrapPage(await request.get(root, { params: query })) }
export async function getMoldMasters(query:MoldMasterQuery):Promise<PageResponse<MoldMaster>> {return unwrapPage(await request.get(root,{params:query}))}
export async function getMoldMaster(id:number,page=1,size=50):Promise<MoldMasterDetail> {return unwrapResponse(await request.get(`${root}/masters/${id}`,{params:{page,size}}))}
export async function createMoldMaster(input:{productLineId:number;styleName:string;moldName:string;engravedCode:string;brandId?:number|null;compatibleModels:string}):Promise<MoldMaster> {return unwrapResponse(await request.post(`${root}/masters`,input))}
export async function getMoldMasterHistory(id:number,page=1):Promise<PageResponse<PlanHistory>> {return unwrapPage(await request.get(`${root}/masters/${id}/history`,{params:{page,size:20}}))}
export async function getMoldMasterReferences(id:number):ReturnType<typeof getMoldReferences> {return unwrapResponse(await request.get(`${root}/masters/${id}/references`))}
export async function getMoldOptions(): Promise<MoldMasterOptions> { return unwrapResponse(await request.get(`${root}/options`)) }
export async function getMold(id: number): Promise<CollaborationRow> { return unwrapResponse(await request.get(`${root}/${id}`)) }
export async function createMold(itemId: number, setName: string, reason:string): Promise<CollaborationRow> { return unwrapResponse(await request.post(root, { itemId, setName, existingPhysical:true, reason })) }
export async function saveMolds(rows: RowPatch[]): Promise<RowResult[]> { return unwrapResponse(await request.post(`${root}/batch-save`, { rows })) }
export async function getMoldHistory(id: number, page = 1): Promise<PageResponse<PlanHistory>> { return unwrapPage(await request.get(`${root}/${id}/history`, { params: { page, size: 20 } })) }
export async function getMoldReferences(id: number): Promise<{ item: CollaborationRow; expressRecords: { projectId: number; trackingNo: string; shippedAt: string | null; latestStatusText: string | null }[] }> { return unwrapResponse(await request.get(`${root}/${id}/references`)) }
export interface MoldInventoryOption { inventoryId: number; inventoryCode: string; inventoryName: string; inventoryType: string; specification: string | null }
export async function getMoldInventoryOptions(keyword: string): Promise<MoldInventoryOption[]> { return unwrapResponse(await request.get(`${root}/inventory-options`, { params: { keyword } })) }

export async function getMoldLifecycle(kind:'item'|'mold_set',id:number|string):Promise<import('@/types/masterDataLifecycle').MasterDataInspection>{return unwrapResponse(await request.get(`${root}/${kind==='item'?'masters/':''}${id}/lifecycle`))}
export async function actOnMold(kind:'item'|'mold_set',id:number|string,action:import('@/types/masterDataLifecycle').MasterDataLifecycleAction,input:import('@/types/masterDataLifecycle').MasterDataLifecycleInput):Promise<unknown>{return unwrapResponse(await request.post(`${root}/${kind==='item'?'masters/':''}${id}/actions/${action}`,input))}
