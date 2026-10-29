// Existing system routes now use real APIs, not a second local fake account store.
export { getPilotUsers as getSystemUsers, getPilotRoles as getSystemRoles, savePilotUser as saveSystemUser, getPilotScopeOptions as getSystemScopeOptions } from './pilot'
import request, { unwrapPage, type PageResponse } from '../request'

export interface OperationLogQuery {
  projectId?:number;page: number; size: number; operatorUserId?: number; action?: string
  createdFrom?: string; createdTo?: string
}
export interface OperationLogItem {
  logId: number; requestId: string | null; operatorUserId: number | null; operatorUserName: string | null
  action: string; businessType: string | null; businessId: string | null
  businessCode: string | null; businessName: string | null; result: string; createdAt: string
  detailJson?: string | null
}
export async function getOperationLogs(query: OperationLogQuery): Promise<PageResponse<OperationLogItem>> {
  if(query.projectId){
    const {projectId,...params}=query;
    const result=unwrapPage<{eventId:number;kind:string;data:unknown;createdAt:string;createdBy:string}>(await request.get(`/product-research/${projectId}/logs`,{params}));
    return {...result,content:result.content.map(row=>({logId:row.eventId,requestId:null,operatorUserId:null,operatorUserName:row.createdBy,action:row.kind,businessType:'PRODUCT',businessId:String(projectId),businessCode:null,businessName:'新品项目',result:'success',createdAt:row.createdAt,detailJson:JSON.stringify(row.data)}))};
  }
  return unwrapPage(await request.get('/operation-logs', { params: query }))
}
