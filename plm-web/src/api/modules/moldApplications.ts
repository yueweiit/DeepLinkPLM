import request, { unwrapResponse, type PageResponse } from '@/api/request'
import type { PhoneCompatibleMold } from './phoneReference'

export interface MoldFile { attachmentId:number; category:string; fileName:string; fileSize:number; contentType:string; sha256:string; downloadStatus:string; selectionId?:string|null; archiveError?:string|null }
export interface MoldActivity { activityId:number; type:string; occurredAt:string|null; actorId:string|null; actorName:string|null; taskId:string|null; payload:Record<string,string> }
export type MoldBusinessStatus = 'draft'|'submitting'|'submission_unknown'|'awaiting_approval'|'approved'|'rejected'|'withdrawn'|'terminated'|'approval_unknown'
export const moldBusinessStatusNames:Record<MoldBusinessStatus,string>={draft:'草稿',submitting:'提交中',submission_unknown:'提交待核对',awaiting_approval:'待 Tiffany 审核',approved:'Tiffany 已同意',rejected:'已拒绝',withdrawn:'已撤回',terminated:'已终止',approval_unknown:'审批待核对'}
export type MoldExecutionIntent='new'|'new_set'|'reuse'
/** Server-derived policy from the real product and persisted application provenance. */
export interface MoldSubmissionPolicy {mode:'research'|'model';requiresCodes:boolean;requiresStandardPhone:boolean;requiresTipoMapping:boolean;allowedTipos:string[];filingMissing:string[]}
export interface MoldTargetOptions{projects:{projectId:number;productName:string}[];items:{itemId:number;moldName:string;compatibleModels:string}[];existingApplications?:{applicationId:number;approvalNo?:string|null;status:string;businessStatus?:MoldBusinessStatus;executionIntent:MoldExecutionIntent}[]}
export interface MoldDraftInput {form:Record<string,string|null>;productLineId:number|null;phoneCodeItemId:number|null;version?:number;phoneModelId?:number|null;executionIntent?:MoldExecutionIntent;targetItemId?:number|null;targetMoldSetId?:number|null;targetProjectId?:number|null;executionReason?:string|null;draftRequestId?:string;newApplication?:boolean}
export interface StartMoldApplication {phoneModelId:number;sourceModelKey?:string;sourceKind?:string;sourceId?:number;productLineId?:number|null;executionIntent?:'new'|'new_set';targetItemId?:number|null;targetProjectId?:number|null;executionReason?:string;requestId?:string;newApplication?:boolean}
export interface MoldHistoricalAssociation {associationOnly:true;reason:string;actorId:number;actorName:string;associatedAt:string;before:Record<string,number|null>;after:Record<string,number|null>}
export interface MoldResearchSource {
  projectId:number;productName:string;productCode:string;phoneModelId:number|null;phoneModelName:string|null;phoneModelIds?:number[];missingFields?:string[];canMaintainProductIdentity?:boolean;currentStepNo?:number;productSpecificCode?:string|null
  ownerUserId?:number|null;ownerName?:string|null;requirements?:string|null;workflowVersion:number;capturedAt:string
  restricted?:boolean;supplierPlanConfirmed?:boolean;suppliersRestricted?:boolean;referencesRestricted?:boolean
  suppliers:{role:string;mode:string;supplierId?:number;supplierCode?:string;supplierName?:string;internalName?:string;scope?:string;basis?:string;reason?:string}[]
  references:{type:string;id:number;name:string;versionNo?:string;checksum?:string}[]
}
export interface ResearchCodingInput { materialCodes?:string[]; operationCode?:string }
export interface ResearchCoding {
  status:'awaiting_approval'|'pending'|'ready';missingFields:string[];missingMessages?:string[]
  materialCodes?:string[];operationCode?:string;operationOptions?:{code:string;name:string}[]
  productSpecificCode?:string;productCodePrefix?:string
  generatedMoldCodes?:string[];generatedProductCodes?:string[]
  products?:{productId:number;code?:string;finishedProductCode?:string;productCode?:string;modelName?:string;colorName?:string;phoneModelId?:number;phoneModelCode?:string;colorCode?:string;operationCode?:string}[]
}
export interface MoldApplication {
  terminal?:boolean;isOwnDraft?:boolean;refusal?:{reason:string|null;actorId:string|null;actorName:string|null;occurredAt:string|null}|null
  applicationId:number; version:number; canEditDraft?:boolean; canDeleteDraft?:boolean; canArchiveFiles?:boolean; sourceSystem:string; instanceId:string|null; approvalNo:string|null; title:string|null
  model:string|null; tipo:string|null; generatedCodes:string|null; form:Record<string,string|null>; status:string; remoteStatus:string|null
  businessStatus?:MoldBusinessStatus; remoteResult?:string|null
  scheduleStatus?:string|null;assetNumber?:string|null
  standardPhone?:{phoneModelId:number;brandName:string;modelName:string;phoneModelCode:string|null}|null;applicantName?:string|null;currentHandlers?:{userId:string|null;name:string|null;taskId:string|null}[];expectedDeliveryDate?:string|null;nextStep?:string|null
  phoneModelId?:number|null;phoneModelCode?:string|null;originalPhoneModelCode?:string|null;sourceSnapshot?:(Record<string,unknown>&{research?:MoldResearchSource})|null;executionIntent?:MoldExecutionIntent
  targetItemId?:number|null;targetMoldSetId?:number|null;targetProjectId?:number|null;executionReason?:string|null;newApplication?:boolean
  provisioningStatus?:'not_requested'|'pending'|'ready'|'blocked';provisioningError?:string|null;executionStatus?:'pending'|'active'|'paused'|'terminated'
  authorization?:{approverUserId:string;approverName:string;taskId?:string|null;activityId?:string|null;approvedAt?:string|null}|null
  executionException?:{state:string;reason:string;key:string}|null
  executionResolution?:{action?:string;reason?:string;actorName?:string;resolvedAt?:string;historicalAssociation?:MoldHistoricalAssociation}|null;historicalAssociation?:MoldHistoricalAssociation;updatedAt?:string;progressStatus?:MoldBusinessStatus
  applicationLinks?:{applicationId:number;phoneModelId?:number|null;projectId:number|null;itemId:number|null;moldSetId:number|null};alreadyExists?:boolean
  approvalGranted:boolean; originatorName:string|null; departmentName:string|null; sourceCreatedAt:string|null; sourceFinishedAt:string|null
  productLineId:number|null; phoneCodeItemId:number|null; projectId:number|null; itemId:number|null; moldSetId:number|null
  lastError:string|null; createdAt:string; activities?:MoldActivity[]; files?:MoldFile[]; previousApplicationId?:number|null
  submissionPolicy?:MoldSubmissionPolicy;reservedCompatibility?:PhoneCompatibleMold|null
  researchCoding?:ResearchCoding|null;canMaintainResearchCoding?:boolean
}
export type SafeResearchApplication=Partial<MoldApplication>&Pick<MoldApplication,'applicationId'|'status'|'version'>
export interface ResearchApplicationSummary {applications:SafeResearchApplication[];activeApplications:SafeResearchApplication[];historyApplications:SafeResearchApplication[];activeCount:number;historyCount:number;totalCount:number;canCreate:boolean}
export type MoldResearchContext=Partial<Omit<MoldApplication,'applicationId'|'productLineId'|'form'|'status'|'sourceSnapshot'>> & {applicationId:null;productLineId:number;form:Record<string,string|null>;status:'draft';sourceSnapshot:Record<string,unknown>&{research:MoldResearchSource};existingApplication?:MoldApplication;existingApplications?:MoldApplication[];activeApplications?:MoldApplication[];historyApplications?:MoldApplication[];existingApplicationNotice?:string}
export interface MoldControl { key:string; id:string; label:string; type:string; required:boolean; options:string[] }
export interface MoldConfig { tipo:string; productLineId:number; productSpecificCode:string; materials:{code:string;partType:string}[]; version:number }
/** Filling eligibility; server submission policy separately governs research approval and model coding. */
export interface MoldApplicationEligibility {eligible:boolean;kind:'catalog'|'research'|'blocked';reason:string|null}
export interface MoldOptions {
  controls:MoldControl[]; configs:MoldConfig[]; productLines:{productId:number;productName:string;productSpecificCode:string|null;canMaintainProductIdentity?:boolean;researchStepNo?:number;salesStatus:import('./productionMetadata').SalesProduct['salesStatus'];moldApplicationEligibility:MoldApplicationEligibility;submissionPolicy?:MoldSubmissionPolicy}[]
  canCreate:boolean; canConvert:boolean; administrator:boolean; writeEnabled:boolean; centralCodeEnabled:boolean; canSubmit:boolean
  originators:{id:string;name:string}[]; processCode:string; syncRunning:boolean
  submitter?:{name:string;dingtalkUserId:string|null;departments:{id:number;name:string}[]};submissionBlockReason?:string|null
}
export interface MoldSummary { counts:{status:string;remoteStatus:string|null;count:number}[]; businessCounts?:{businessStatus:MoldBusinessStatus;count:number}[]; ready:number }
export interface MoldQuery { projectId?:number;unlinked?:boolean;page:number;size:number;keyword?:string;tipo?:string;status?:string;originator?:string;from?:string;until?:string }
export interface MoldHistoricalPreview {
  application:MoldApplication;selectedProductLineId:number|null;standardPhone:CompatibleHistoricalPhone|null
  productLines:{productId:number;productName:string}[]
  projects:{projectId:number;productName:string;model:string|null;phoneModelId:number|null;phoneModelCode:string|null;linkedItemId?:number|null;blockedReason:string|null}[]
  items:{itemId:number;moldName:string;compatibleModels:string|null;engravedCode:string|null}[]
  sets:{moldSetId:number;itemId:number;setNo:number;setName:string;pcCode:string|null;tpuCode:string|null;blockedReason:string|null}[]
}
export interface CompatibleHistoricalPhone {phoneModelId:number;brandName:string;modelName:string;phoneModelCode:string|null}
export interface MoldHistoricalInput {version:number;phoneModelId:number;productLineId:number;projectId:number|null;itemId:number|null;moldSetId:number|null;reason:string;confirmPhoneCorrection:boolean}
export const root='/mold-applications'
export async function getMoldApplicationConfig():Promise<{enabled:boolean}> { return unwrapResponse(await request.get(`${root}/config`)) }
export async function getMoldOptions():Promise<MoldOptions> { return unwrapResponse(await request.get(`${root}/options`)) }
export async function getMoldApplications(query:MoldQuery):Promise<PageResponse<MoldApplication>> { return unwrapResponse(await request.get(root,{params:query})) }
export async function getMoldSummary(scope?:{projectId?:number;unlinked?:boolean}):Promise<MoldSummary> { return unwrapResponse(await request.get(`${root}/summary`,{params:scope})) }
export async function getMoldApplication(id:number):Promise<MoldApplication> { return unwrapResponse(await request.get(`${root}/${id}`)) }
export async function saveMoldApplication(id:number|null,data:MoldDraftInput):Promise<MoldApplication> { return unwrapResponse(id?await request.put(`${root}/${id}`,data):await request.post(root,data)) }
export interface MoldReuseSelection {phoneModelId:number;itemId?:number;moldSetId?:number;compatibilityDigest?:string}
export async function reserveMoldCodes(id:number,version:number,reuse?:MoldReuseSelection):Promise<MoldApplication> {return unwrapResponse(await request.post(`${root}/${id}/codes`,{version,...reuse}))}
export async function uploadMoldFile(id:number,category:string,file:File,selectionId?:string):Promise<MoldApplication> {const data=new FormData();data.append('file',file);data.append('category',category);if(selectionId)data.append('selectionId',selectionId);return unwrapResponse(await request.post(`${root}/${id}/files`,data))}
export async function selectMoldFiles(id:number,selections:{selectionId:string;category:string;fileName:string;fileSize:number;contentType:string}[]):Promise<MoldApplication>{return unwrapResponse(await request.post(`${root}/${id}/file-selections`,selections))}
export async function discardMoldSelection(id:number,file:number):Promise<MoldApplication>{return unwrapResponse(await request.post(`${root}/${id}/files/${file}/discard-selection`))}
export async function downloadMoldFile(id:number,file:number,signal?:AbortSignal):Promise<Blob> {return (await request.get(`${root}/${id}/files/${file}`,{responseType:'blob',...(signal?{signal}:{})})).data}
export async function archiveMoldFiles(id:number):Promise<MoldApplication> {return unwrapResponse(await request.post(`${root}/${id}/files/archive`))}
export async function submitMoldApplication(id:number,version:number,departmentId?:number):Promise<MoldApplication> {return unwrapResponse(await request.post(`${root}/${id}/submit`,{version,departmentId,confirmFormal:true},{timeout:180000}))}
export async function withdrawMoldApplication(id:number,version:number):Promise<MoldApplication> {return unwrapResponse(await request.post(`${root}/${id}/withdraw`,{version},{timeout:60000}))}
export async function syncMoldApplication(id:number):Promise<MoldApplication> {return unwrapResponse(await request.post(`${root}/${id}/sync`,{}, {timeout:90000}))}
export async function syncMoldApplications(from:string,until:string):Promise<{synced:number;failedInstanceIds:string[]}> {return unwrapResponse(await request.post(`${root}/sync`,{from,until},{timeout:600000}))}
export async function saveMoldConfig(config:MoldConfig|Omit<MoldConfig,'version'>):Promise<void> {unwrapResponse(await request.put(`${root}/configs`,config))}

export type MoldStartResult=Omit<MoldApplication,'applicationId'> & {applicationId?:number;requiresExplicitIntent?:boolean;existingMold?:boolean}
export async function getResearchMoldApplicationContext(data:{projectId:number;version:number;phoneModelId?:number|null}):Promise<MoldResearchContext>{return unwrapResponse(await request.get(`${root}/research-context`,{params:data}))}
export async function startResearchMoldApplication(data:{projectId:number;phoneModelId:number|null;requestId:string;version:number;draft?:MoldDraftInput}):Promise<MoldStartResult>{return unwrapResponse(await request.post(`${root}/from-research`,data))}
export async function startMoldApplication(data:StartMoldApplication):Promise<MoldStartResult>{return unwrapResponse(await request.post(`${root}/from-phone`,data))}
export async function getMoldTargetOptions(phoneModelId:number,productLineId:number):Promise<MoldTargetOptions>{return unwrapResponse(await request.get(`${root}/target-options`,{params:{phoneModelId,productLineId}}))}
export async function retryMoldProvision(id:number,version:number):Promise<MoldApplication>{return unwrapResponse(await request.post(`${root}/${id}/provision-retry`,{version}))}
export async function saveResearchMoldCoding(id:number,data:ResearchCodingInput&{version:number}):Promise<MoldApplication>{return unwrapResponse(await request.post(`${root}/${id}/research-coding`,data))}
export async function resolveMoldExecution(id:number,data:{version:number;action:'resume'|'terminate';reason:string}):Promise<MoldApplication>{return unwrapResponse(await request.post(`${root}/${id}/execution-resolution`,data))}

export async function correctMoldTargets(id:number,data:{version:number;executionIntent:'new'|'new_set';targetItemId:number|null;targetProjectId:number|null;reason:string}):Promise<MoldApplication>{return unwrapResponse(await request.post(`${root}/${id}/provision-targets`,data))}
export async function previewMoldHistoricalAssociation(id:number,data:{phoneModelId?:number|null;productLineId?:number|null}={}):Promise<MoldHistoricalPreview>{return unwrapResponse(await request.post(`${root}/${id}/historical-association-preview`,data))}
export async function associateMoldHistory(id:number,data:MoldHistoricalInput):Promise<MoldApplication>{return unwrapResponse(await request.post(`${root}/${id}/historical-association`,data))}

export interface HistoryGap {code:string;message:string;path:string;query:Record<string,string|number>}
export interface HistoryDecision {applicationId:number;approvalNo:string|null;instanceId:string|null;version:number;model:string;tipo:string;phoneModelId:number|null;phoneCode:string|null;phoneName:string|null;productLineId:number|null;productLineName:string;projectId:number|null;itemId:number|null;moldSetId:number|null;createProject:boolean;actionable:boolean;state:string;fingerprint:string;gaps:HistoryGap[];evidence:unknown[]}
export interface HistoryPreview {total:number;actionable:number;linked:number;groups:{groupKey:string;phoneName:string;phoneCode:string|null;applications:HistoryDecision[]}[];items:HistoryDecision[]}
export async function getMoldHistoryReconciliation():Promise<HistoryPreview>{return unwrapResponse(await request.get('/mold-applications/historical-reconciliation'))}
export async function applyMoldHistoryReconciliation(items:Pick<HistoryDecision,'applicationId'|'version'|'fingerprint'|'phoneModelId'>[],reason:string):Promise<{saved:number;results:{applicationId:number;status:string;message?:string}[];remaining:HistoryPreview}>{return unwrapResponse(await request.post('/mold-applications/historical-reconciliation',{items,reason}))}

export async function deleteMoldDraft(id:number,version:number):Promise<{applicationId:number;deleted:boolean}>{return unwrapResponse(await request.delete(`/mold-applications/${id}`,{params:{version}}))}
