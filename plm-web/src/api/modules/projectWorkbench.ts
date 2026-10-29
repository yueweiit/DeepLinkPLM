import request, { unwrapResponse, type PageResponse } from '@/api/request'
import type { MoldApplication } from './moldApplications'
import type { CollaborationRow } from '@/types/collaboration'
export type ProjectKind='product_research'|'model_extension'
export type ProjectView='active'|'completed'|'stopped'|'all'
export interface ProjectCapabilities {applications:boolean;schedules:boolean;molds:boolean;documents:boolean;bom:boolean;process:boolean;finance:boolean}
export interface WorkbenchProject {
  historicalSkuCount?:number
  developmentMode?: string
  projectId:number;projectName:string;projectCode:string;projectKind:ProjectKind|null;registeredAt:string|null;projectState:ProjectView
  productType:string;productLineId:number|null;productLineName:string|null;parentProductId:number|null;phoneModelId:number|null;phoneName:string|null;brandName:string|null;phoneModelCode:string|null;originalModel:string|null
  ownerName:string|null;expectedDeliveryDate:string|null;flowNode:string;flowStarted:boolean;phase:string;nextStep:string;risks:string[];stopReason?:string
  applicationCount:number|null;archiveCount:number|null;moldSetCount:number|null;physicalSetCount:number|null;makingSetCount:number|null;overdueTaskCount:number|null
}
export interface ProjectOverview extends WorkbenchProject {
  capabilities:ProjectCapabilities;historicalRecord:boolean;applications:MoldApplication[];archives:{itemId:number;moldName:string;engravedCode:string|null}[];moldSets:CollaborationRow[]
  historicalSkus?:import('@/types/marketWatch').MarketPhoneProduct[]
  childProjects:{projectId:number;projectName:string;projectKind:ProjectKind;status:string}[];documentCount?:number;bomCount?:number;relatedExpenseCount?:number
  directFinance?:{currencyGroups:{currencyCode:string;recordCount:number;requestedTotal:number|null;approvedTotal:number|null;declaredTotal:number|null;erpPostedNet:number|null}[];rule:string}
}
export interface WorkbenchQuery {page:number;size:number;keyword?:string;kind:'all'|ProjectKind;view:ProjectView}
export interface WorkbenchPage extends PageResponse<WorkbenchProject>{stats:Record<ProjectView,number>;capabilities:ProjectCapabilities;unlinkedApplications?:{total:number;counts:{status:string;count:number}[]}}
export async function getWorkbenchProjects(params:WorkbenchQuery):Promise<WorkbenchPage>{return unwrapResponse(await request.get('/workbench/projects',{params}))}
export async function getProjectBusinessOverview(id:number):Promise<ProjectOverview>{return unwrapResponse(await request.get(`/projects/${id}/business-overview`))}
