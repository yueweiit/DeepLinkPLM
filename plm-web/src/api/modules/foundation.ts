import type {
  BomCenterRow,
  FileSection,
  FoundationProductRef,
  InventoryListRow,
  InventoryTreeNode,
  ProductDetailPresentation,
  TestCategoryItem,
  TestRecordItem
} from '@/types/foundation'

import request, { unwrapPage, unwrapResponse, type PageResponse } from '../request'
import { notConnected } from '../notConnected'

export function getFoundationProducts(): Promise<FoundationProductRef[]> {
  return notConnected('基础产品引用')
}

export function getFileSections(): Promise<FileSection[]> {
  return notConnected('文件中心分组')
}

export function getTestCenterSnapshot(): Promise<{
  categories: TestCategoryItem[]
  records: TestRecordItem[]
}> {
  return notConnected('测试中心')
}

export async function getInventoryCenterSnapshot(): Promise<{
  tree: InventoryTreeNode[]
  items: InventoryListRow[]
}> {
  return unwrapResponse(await request.get('/inventories/center-snapshot'))
}

export interface InventoryPageQuery {
  page: number
  size: number
  keyword?: string
  nodeId?: string
  timeRange?: string
}

export async function getInventoryPage(params: InventoryPageQuery): Promise<PageResponse<InventoryListRow>> {
  return unwrapPage(await request.get('/inventories', { params }))
}

export async function getInventoryGroups(): Promise<InventoryTreeNode[]> {
  return unwrapResponse(await request.get('/inventories/groups'))
}

export function getBomCenterRows(): Promise<BomCenterRow[]> {
  return notConnected('BOM 中心列表')
}

export function getProductPresentation(_productId: number): Promise<ProductDetailPresentation> {
  return notConnected('产品详情展示')
}

export interface InventoryMaster {
 id:number;code:string;name:string;version:number;status:string;deleted?:boolean;remark:string;updatedAt:string;
 spec?:string;unit?:string;inventoryType?:string;groupId?:number|null;quantity?:number;stockStatus?:string;supplierName?:string;
 parentId?:number|null;sourceSystem?:string;sourceNameReadonly?:boolean
}
export async function getInventoryMaster(id:number,group=false):Promise<InventoryMaster>{return unwrapResponse(await request.get(`/inventories/${group?'groups/':''}${id}`))}
export async function getInventoryGroupMasters():Promise<InventoryMaster[]>{return unwrapResponse(await request.get('/inventories/groups/maintenance'))}
export async function saveInventoryMaster(id:number|undefined,data:Partial<InventoryMaster>&{requestId:string;reason?:string},group=false):Promise<InventoryMaster>{const url='/inventories'+(group?'/groups':'')+(id?'/'+id:'');return unwrapResponse(await (id?request.put(url,data):request.post(url,data)))}
export async function getInventoryReferences(id:number|string,group=false):Promise<import('@/types/masterDataLifecycle').MasterDataInspection>{return unwrapResponse(await request.get(`/inventories/${group?'groups/':''}${id}/references`))}
export async function inventoryLifecycle(id:number|string,action:import('@/types/masterDataLifecycle').MasterDataLifecycleAction,payload:{version:number;requestId:string;reason:string},group=false){return unwrapResponse<InventoryMaster>(await request.post(`/inventories/${group?'groups/':''}${id}/actions/${action}`,payload))}

