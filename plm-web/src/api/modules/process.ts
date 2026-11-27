import type { ProcessCenterSnapshot } from '@/types/process'
import type { ProcessRouteRelation } from '@/types/process'
import request, { unwrapResponse } from '../request'
import type { RouteEdge, RouteNode } from './masterData'

export interface MesOperation {
  objectId: number
  sourceCode: string
  source: {
    operation_name: string; operation_name_es?: string
    category?: string; category_es?: string; version?: string; status?: string
    execution_mode?: string; scheduling_mode?: string; requires_scheduling?: boolean
    requires_qc?: boolean; requires_report?: boolean; requires_material?: boolean; requires_machine?: boolean
  }
}
export interface MesRoute {
  objectId: number; sourceCode: string; productId: number | null
  seriesName?: string; seriesNameEs?: string; routeStatus?: string
  operationIds: Array<number | null>
  source: { product_code?: string; route_name: string; route_name_es?: string; version?: string; status?: string; updated_at?: string }
}
export interface ProcessCatalog { routes: MesRoute[]; operations: MesOperation[] }
export interface ProcessGraph { nodes: RouteNode[]; edges: RouteEdge[] }
export async function getCurrentProcesses() {
  return unwrapResponse<ProcessCatalog>(await request.get('/process-routes/current'))
}
export async function getCurrentProcessGraph(id: number) {
  return unwrapResponse<ProcessGraph>(await request.get(`/process-routes/${id}/graph`,{params:{current:true}}))
}

/* ========== 工艺中心 ========== */

export async function getProcessCenterSnapshot(): Promise<ProcessCenterSnapshot> {
  const response = await request.get('/process-center/snapshot')
  return unwrapResponse<ProcessCenterSnapshot>(response)
}

export async function getProcessRouteRelations(processId: number): Promise<ProcessRouteRelation> {
  const response = await request.get(`/process-routes/${processId}/relations`)
  return unwrapResponse<ProcessRouteRelation>(response)
}

/* ========== 项目级工艺路线（文档 7.5） ========== */

export interface ProcessRouteVO {
  processId: number
  productId: number
  processCode: string
  processName: string
  processType: string
  versionNo: string
  status: 'draft' | 'confirmed' | 'locked' | 'changed' | 'archived'
  routeTemplateCode?: string | null
  routeTemplateVersion?: string | null
  applicableModel?: string | null
  applicableColor?: string | null
  linkedBomVersionNo?: string | null
  finalSelected?: boolean | null
  frozenAt?: string | null
  frozenBy?: string | null
  remark?: string | null
  operations: ProcessOperationVO[]
  graph?:{nodes:import('./masterData').RouteNode[];edges:import('./masterData').RouteEdge[]}
  graphOriginalHash?:string
  sourceRouteCode?:string
}

export interface ProcessOperationVO {
  processId: number
  parentProcessId: number
  operationMasterProcessId?: number | null
  operationSource?: 'master' | 'imported_snapshot' | 'manual_snapshot' | null
  processCode?: string | null
  operationCode?: string | null
  operationCraftCode?: string | null
  materialStatusCode?: string | null
  finishedProductFlag?: boolean | null
  businessOperationCode?: string | null
  businessOperationCodeManualFlag?: boolean | null
  productSpecificCode?: string | null
  phoneModelCode?: string | null
  colorCode?: string | null
  generatedFinishedProductCode?: string | null
  codeGenerationContext?: string | null
  sequenceNo: number
  nodeCode?:string
  localNodeCode?:string
  positionX?:number
  positionY?:number
  processName: string
  processParamJson?: string | null
  standardTimeMins?: number | null
  qualityRequirement: string
  status: 'draft' | 'confirmed' | 'locked' | 'changed' | 'archived'
  remark?: string | null
}

export interface ProcessOperationSavePayload {
  sequenceNo: number
  nodeCode?:string
  localNodeCode?:string
  positionX?:number
  positionY?:number
  operationMasterProcessId?: number | null
  operationSource?: 'master' | 'imported_snapshot' | 'manual_snapshot' | null
  operationCode?: string
  operationCraftCode?: string
  materialStatusCode?: string
  finishedProductFlag?: boolean
  businessOperationCode?: string
  businessOperationCodeManualFlag?: boolean
  productSpecificCode?: string
  phoneModelCode?: string
  colorCode?: string
  generatedFinishedProductCode?: string
  codeGenerationContext?: string
  processName: string
  processParamJson?: string
  standardTimeMins?: number | null
  qualityRequirement: string
  remark?: string
}

export interface ProcessRouteSavePayload {
  processName: string
  versionNo: string
  routeTemplateCode?: string
  routeTemplateVersion?: string
  copyTemplateOperations?: boolean
  applicableModel?: string
  applicableColor?: string
  linkedBomVersionNo?: string
  finalSelected?: boolean
  remark?: string
  operations: ProcessOperationSavePayload[]
  graphOriginalHash?:string
  sourceProcessId?:number
  sourceRouteCode?:string
  edges?:Array<{fromNodeCode:string;toNodeCode:string;edgeNo?:number;relationType:string;label?:string;condition?:unknown}>
}

export interface ProcessRouteTemplateOperationVO {
  operationCode: string
  operationMasterProcessId?: number | null
  operationCraftCode?: string | null
  materialStatusCode?: string | null
  finishedProductFlag?: boolean | null
  businessOperationCode?: string | null
  businessOperationCodeManualFlag?: boolean | null
  productSpecificCode?: string | null
  phoneModelCode?: string | null
  colorCode?: string | null
  generatedFinishedProductCode?: string | null
  codeGenerationContext?: string | null
  sequenceNo: number
  processName: string
  processParamJson?: string | null
  standardTimeMins?: number | null
  qualityRequirement: string
  remark?: string | null
}

export interface ProcessRouteTemplateVO {
  routeTemplateCode: string
  routeTemplateName: string
  productCode?: string | null
  versionNo: string
  status?: string
  defaultTemplate?: boolean
  priority?: number
  operations: ProcessRouteTemplateOperationVO[]
}

export interface ProcessRouteTemplateQuery {
  productCode?: string
  onlyDefault?: boolean
}

/* ========== 基础资料：工艺管理 / 工序库 ========== */

export interface ProcessOperationMasterQuery {
  keyword?: string
  processCategory?: string
  operationType?: string
  status?: string
}

export interface ProcessOperationMasterVO {
  processId: number
  processCode: string
  processName: string
  processCategory?: string | null
  operationType?: string | null
  operationCraftCode?: string | null
  defaultStandardTimeMins?: number | null
  defaultQualityRequirement?: string | null
  defaultProcessParamJson?: string | null
  needWorkstation?: boolean | null
  workstationType?: string | null
  status: 'draft' | 'confirmed' | 'locked' | 'archived'
  remark?: string | null
  updatedAt?: string | null
}

export interface ProcessOperationMasterSavePayload {
  processCode: string
  processName: string
  processCategory: string
  operationType: string
  operationCraftCode?: string | null
  defaultStandardTimeMins?: number | null
  defaultQualityRequirement?: string | null
  defaultProcessParamJson?: string | null
  needWorkstation?: boolean
  workstationType?: string | null
  remark?: string | null
}

/** GET /api/v1/process-operation-masters */
export async function getProcessOperationMasters(params: ProcessOperationMasterQuery = {}): Promise<ProcessOperationMasterVO[]> {
  const response = await request.get('/process-operation-masters', { params })
  return unwrapResponse<ProcessOperationMasterVO[]>(response)
}

/** POST /api/v1/process-operation-masters */
export async function createProcessOperationMaster(payload: ProcessOperationMasterSavePayload): Promise<ProcessOperationMasterVO> {
  const response = await request.post('/process-operation-masters', payload)
  return unwrapResponse<ProcessOperationMasterVO>(response)
}

/** PUT /api/v1/process-operation-masters/{processId} */
export async function updateProcessOperationMaster(
  processId: number,
  payload: ProcessOperationMasterSavePayload
): Promise<ProcessOperationMasterVO> {
  const response = await request.put(`/process-operation-masters/${processId}`, payload)
  return unwrapResponse<ProcessOperationMasterVO>(response)
}

/** POST /api/v1/process-operation-masters/{processId}/confirm */
export async function confirmProcessOperationMaster(processId: number): Promise<ProcessOperationMasterVO> {
  const response = await request.post(`/process-operation-masters/${processId}/confirm`)
  return unwrapResponse<ProcessOperationMasterVO>(response)
}

/** POST /api/v1/process-operation-masters/{processId}/archive */
export async function archiveProcessOperationMaster(processId: number): Promise<ProcessOperationMasterVO> {
  const response = await request.post(`/process-operation-masters/${processId}/archive`)
  return unwrapResponse<ProcessOperationMasterVO>(response)
}

/** GET /api/v1/process-route-templates */
export async function getProcessRouteTemplates(params: ProcessRouteTemplateQuery = {}): Promise<ProcessRouteTemplateVO[]> {
  const response = await request.get('/process-route-templates', { params })
  return unwrapResponse<ProcessRouteTemplateVO[]>(response)
}

/** GET /api/v1/projects/{projectId}/process-routes */
export async function getProjectProcessRoutes(projectId: number): Promise<ProcessRouteVO[]> {
  const response = await request.get(`/projects/${projectId}/process-routes`)
  return unwrapResponse<ProcessRouteVO[]>(response)
}

/** GET /api/v1/process-routes/{processId} */
export async function getProcessRouteDetail(processId: number): Promise<ProcessRouteVO> {
  const response = await request.get(`/process-routes/${processId}`)
  return unwrapResponse<ProcessRouteVO>(response)
}

/** POST /api/v1/projects/{projectId}/process-routes */
export async function createProcessRoute(projectId: number, payload: ProcessRouteSavePayload) {
  const response = await request.post(`/projects/${projectId}/process-routes`, payload)
  return unwrapResponse<ProcessRouteVO>(response)
}

/** PUT /api/v1/process-routes/{processId} */
export async function updateProcessRoute(processId: number, payload: ProcessRouteSavePayload) {
  const response = await request.put(`/process-routes/${processId}`, payload)
  return unwrapResponse<ProcessRouteVO>(response)
}

/** DELETE /api/v1/process-routes/{processId} */
export async function deleteProcessRoute(processId: number): Promise<void> {
  await request.delete(`/process-routes/${processId}`)
}

/** POST /api/v1/process-routes/{processId}/freeze */
export async function freezeProcessRoute(processId: number) {
  const response = await request.post(`/process-routes/${processId}/freeze`)
  return unwrapResponse<ProcessRouteVO>(response)
}
