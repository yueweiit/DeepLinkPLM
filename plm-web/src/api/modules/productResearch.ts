import type { PhoneObjectPage, PhoneObjectOption, ObjectPhoneGroup, PhoneObjectQuery } from '@/components/PhoneObjectSelect/types'
import request, { unwrapResponse, type PageResponse } from "../request";
import type { AttachmentVO } from "./attachment";

export type MaterialType = "file" | "bom" | "process" | "link";
export interface MaterialReference {
  type: MaterialType;
  id: number;
}
export interface DevelopmentMaterial extends MaterialReference {
  name: string;
  versionNo: string;
  status: string;
  category?: string;
  familyId?: number;
  attachment?: AttachmentVO;
  content?: Record<string, unknown>;
  checksum?: string;
}
export interface DevelopmentProfileInput {
  productName: string;
  model: string;
  requirements: string;
  targetCost: number | null;
  expectedDeliveryDate: string | null;
  phoneModelIds?: number[];
  pendingReason?: string;
  currency?: string;
  costUnit?: string;
  notes?: string;
  ownerUserId?: number | null;
  references?: MaterialReference[];
  version?: number;
  changeReason?: string;
}
export interface DevelopmentRoundInput {
  kind: "sample" | "test" | "pilot";
  recordDate: string | null;
  title: string;
  result: "pending" | "passed" | "failed";
  issues: string;
  changes: string;
  retestResult: string;
  references: MaterialReference[];
  confirm: boolean;
  details?: RoundFacts;
  version?: number;
  requestId?: string;
}
export interface DevelopmentRound
  extends Omit<DevelopmentRoundInput, "confirm" | "references"> {
  legacyBasis?: boolean;
  roundId: number;
  roundNo: number;
  status: "draft" | "confirmed";
  confirmedAt?: string;
  confirmedBy?: string;
  references: DevelopmentMaterial[];
  voided?: boolean;
}
export interface DevelopmentRelease {
  releaseId: number;
  packageNo: number;
  legacyVersionNo?: string;
  basis?: Record<string, unknown>;
  versionNo: string;
  publishedAt: string;
  publishedBy: string;
  references: DevelopmentMaterial[];
  roundsSnapshot: DevelopmentRound[];
  productSnapshot: Record<string, unknown>;
  missingItems: { code: string; message: string }[];
  exceptionReason?: string;
  supplementDueDate?: string;
  responsibleUserId?: number;
}
export interface ProductResearchRow {
  phoneModels?:{phoneModelId:number;modelName:string;brandName:string;status?:string;brandStatus?:string}[]
  moldApplicationSummary?:import('./moldApplications').ResearchApplicationSummary
  productId: number;
  productName: string;
  productCode: string;
  model: string;
  status: string;
  currentStage: string;
  workflowComplete?: boolean;
  supplierNames?: string[];
  supplierFeedbackSummary?: SupplierFeedbackSummary;
  nextAction?: string;
  currentStepNo: number;
  ownerName: string;
  expectedDeliveryDate?: string;
  targetCost?: number;
  roundCount: number;
}
export interface ProductResearchDetail extends DevelopmentProfileInput {
  productId: number;
  productCode: string;
  versionNo: string;
  ownerName: string;
  ownerUserId?: number | null;
  currentStepNo: number;
  canPublish?: boolean;
  salesStatus?: string;
  productSpecificCode?: string;
  canMaintainProductIdentity?: boolean;
  canLaunch?: boolean;
  launchIssues?: {code:string;message:string;target?:string}[];
  canConfirmEngineering?: boolean;
  canChangeSupplier?: boolean;
  supplierFeedback?: SupplierFeedback[];
  supplierFeedbackRestricted?: boolean;
  supplierFeedbackSummary?: SupplierFeedbackSummary;
  engineering?: EngineeringSummary;
  canScheduleWrite?: boolean;
  moldApplicationEntry?: {eligible:boolean;reason?:string|null;phoneModelIds:number[];canCreate:boolean};
  workflow: ResearchWorkflow;
  events: ResearchEvent[];
  releases: DevelopmentRelease[];
  status: string;
  developmentMode?:string;
  lockStatus?: string;
  canWrite: boolean;
  rounds: DevelopmentRound[];
  materials: DevelopmentMaterial[];
  release: DevelopmentRelease | null;
  reviewGates?: {gate:string;status:string;reason:string;recordId?:number}[];
  bomRoutes?: { routeId:number; bomId:number; processId:number; name:string }[];
  unitCostOptions?: { id:number; title:string; bomId:number; bomVersion:string; basisType?:'bom'|'supplier_quote'; confirmedAt:string; confirmedBy:string; voidReason?:string }[];
}

export interface SupplierFeedbackSummary {
  cannotAcceptSupplierCount: number;
  nextSupplierPending: boolean;
  hasFeedback: boolean;
  restricted: boolean;
}

export interface SupplierAssignment {
  mode: 'supplier' | 'internal';
  supplierCode?: string;
  internalName?: string;
  role: 'mold' | 'component' | 'processing' | 'finished_product';
  scope: string;
  basis: string;
}
export interface ResearchSupplierOption {
  businessCategories?: import('@/types/supplier').SupplierBusinessCategory[];
  supplierId?: number;
  supplierCode: string;
  supplierName: string;
  status?: string;
  version?: number;
  needsReview?: boolean;
}
export type SupplierFeedbackResult = 'pending' | 'can_accept' | 'cannot_accept';
export interface SupplierFeedbackInput {
  stage?: number;
  feedbackId?: number;
  supplierCode?: string;
  result?: SupplierFeedbackResult;
  scope?: string;
  feedbackDate?: string | null;
  reason?: string;
  references?: MaterialReference[];
  changeReason?: string;
  voided?: boolean;
  releaseCurrent?: boolean;
  previousSupplierCode?: string;
  role?: SupplierAssignment['role'];
  assignmentScope?: string;
}
export interface SupplierFeedback extends Omit<SupplierFeedbackInput, 'references' | 'result' | 'reason'> {
  feedbackId: number;
  eventId: number;
  revision: number;
  stage: number;
  result: SupplierFeedbackResult;
  reason: string;
  supplierSnapshot: {supplierId?: number; supplierCode: string; supplierName: string; version?: number};
  references: DevelopmentMaterial[];
  recordedAt: string;
  recordedBy: string;
  history?: SupplierFeedback[];
  referencesRestricted?: boolean;
}
export interface EngineeringSummary {
  confirmed: boolean;
  current: boolean;
  basis?: {
    revision?: number;
    confirmedAt?: string;
    confirmedBy?: string;
    supplierAssignments?: SupplierAssignment[];
    references?: DevelopmentMaterial[];
    method?: string;
    needsMold?: boolean;
    reason?: string;
  };
  checks: StageReadiness['checks'];
}

export const getProductResearch = async (params: {
  keyword?: string;
  state?: string;
  page?: number;
  size?: number;
  stage?: number;
  flow?:string;
}) =>
  unwrapResponse<PageResponse<ProductResearchRow>>(
    await request.get("/product-research", { params }),
  );
export const getProductResearchDetail = async (id: number) =>
  unwrapResponse<ProductResearchDetail>(
    await request.get(`/product-research/${id}`),
  );
export const updateProductResearch = async (
  id: number,
  input: DevelopmentProfileInput,
) =>
  unwrapResponse<ProductResearchDetail>(
    await request.put(`/product-research/${id}`, input),
  );
export const saveResearchDesignDraft = async (id:number,profile:DevelopmentProfileInput,plan:Record<string,unknown>) =>
  unwrapResponse<ProductResearchDetail>(await request.put(`/product-research/${id}/design`,{profile,plan}));
export interface DesignDraftInput {
  profile: DevelopmentProfileInput;
  plan: Record<string, unknown>;
}
export interface StageReadiness {
  version: number;
  ready: boolean;
  checks: { code: string; title: string; target: string; complete: boolean; message: string; blocking?: boolean }[];
}
export const checkResearchDesign = async (id:number,input:DesignDraftInput) =>
  unwrapResponse<StageReadiness>(await request.post(`/product-research/${id}/design-readiness`,input));
export interface StageReadinessInput { stage: number; sampleRoundId?: number; releaseDraft?: Record<string,unknown>; draft?: Record<string,unknown> }
export const checkResearchStage = async (id:number,input:StageReadinessInput) =>
  unwrapResponse<StageReadiness>(await request.post(`/product-research/${id}/stage-readiness`,input));
export const saveDevelopmentRound = async (
  id: number,
  roundId: number | null,
  input: DevelopmentRoundInput,
) =>
  unwrapResponse<DevelopmentRound>(
    await (roundId
      ? request.put(`/product-research/${id}/rounds/${roundId}`, input)
      : request.post(`/product-research/${id}/rounds`, input)),
  );
export const deleteDevelopmentDraft = async (
  id: number,
  roundId: number,
  version?: number,
) =>
  unwrapResponse<void>(
    await request.delete(`/product-research/${id}/rounds/${roundId}`, {
      params: { version },
    }),
  );
export const deleteProductResearchDraft = async (id:number,version:number) =>
  unwrapResponse<void>(await request.delete(`/product-research/${id}`,{params:{version}}));
export const previewUnitCost = async (id:number,input:UnitCostSheet) =>
  unwrapResponse<UnitCostSheet>(await request.post(`/product-research/${id}/unit-cost/preview`,input));

export const materialKey = (material: MaterialReference) =>
  `${material.type}:${material.id}`;
export function materialReferences(keys: string[]): MaterialReference[] {
  return keys.map((key) => {
    const [type, id] = key.split(":");
    return { type: type as MaterialType, id: Number(id) };
  });
}

export interface RoundFacts {
  samplePurpose?: 'prototype_3d'|'mold_trial';
  requirementVersion?: number;
  revisionNo?: number;
  phoneModelIds: number[];
  sourceRoundId?: number | null;
  previousRoundId?: number | null;
  feedbackRoundId?: number | null;
  makerName?: string;
  supplierCode?: string;
  quoteEventId?: number | null;
  moldMode?: "none" | "existing" | "new";
  moldSetId?: number | null;
  applicationId?: number | null;
  designSummary?: string;
  testContent?: string;
  actualResult?: string;
  blockingIssues?: boolean;
  producedQty?: number | null;
  inspectedQty?: number | null;
  defectQty?: number | null;
  inspectionMethod?: string;
  batchNo?: string;
}
export interface ReleaseDraft {
  pilotRoundId?: number | null;
  phoneModelIds: number[];
  references: MaterialReference[];
  confirmationDate?: string;
  sampleNote?: string;
  sampleLocation?: string;
  documentationOnly?: boolean;
  documentationReason?: string;
  responsibleUserId?: number;
  unitCostVersionId?: number | null;
}
export interface ResearchWorkflow {
  version: number;
  profileDraft?: DevelopmentProfileInput;
  requirement?: DevelopmentProfileInput & {
    requirementVersion: number;
    confirmedAt?: string;
  };
  requirementVersion?: number;
  selectedRoundId?: number;
  feedback?: {
    sourceRoundId: number;
    targetStage: number;
    baseSampleRoundId?: number;
    completedByRoundId?: number;
    source?: Record<string, unknown>;
  };
  releaseDraft?: ReleaseDraft;
  finalized?: ReleaseDraft;
  revision?: {
    revisionNo: number;
    stage: number;
    reason: string;
    impact: string;
    technical: boolean;
  };
  [key: string]: unknown;
}
export interface ResearchEvent {
  eventId: number;
  kind: string;
  data: Record<string, unknown>;
  createdAt: string;
  createdBy: string;
}
export type WorkflowAction =
  | "shipment_confirm"
  | "supplier_change"
  | "supplier_feedback"
  | "engineering_confirm"
  | "review_setup" | "review_save" | "review_delete" | "review_void" | "round_void"
  | "confirm_design_basis" | "mold_unlink"
  | "unit_cost_save" | "unit_cost_confirm" | "unit_cost_delete" | "unit_cost_void" | "unit_cost_from_bom"
  | "plan_draft" | "launch_draft" | "mold_link" | "mold_node" | "mold_restart" | "no_mold" | "cost" | "promotion" | "presale" | "launch"
  | "revision_handover"
  | "revision_return"
  | "confirm_requirement"
  | "select_round"
  | "release_draft"
  | "finalize"
  | "quote"
  | "link"
  | "inspection"
  | "quantity_correction"
  | "supplement"
  | "gap_plan"
  | "transfer"
  | "receipt"
  | "start_revision";
export const researchAction = async (
  id: number,
  action: WorkflowAction,
  version: number,
  data: object,
  requestId = crypto.randomUUID(),
) =>
  unwrapResponse<ProductResearchDetail>(
    await request.post(`/product-research/${id}/workflow`, {
      action,
      version,
      data,
      requestId,
    }),
  );
export const getResearchOwnerOptions = async () =>
  unwrapResponse<{
    owners: { userId: number; name: string }[];
    suppliers?: ResearchSupplierOption[];
    canSelectSupplier?: boolean;
  }>(await request.get("/product-research/options"));

export interface ResearchBusinessRow {
  productId:number; productName:string; productCode:string; model:string; projectStatus:string;
  currentStepNo:number;hasUnitCostDraft?:boolean;unitCostVersionCount?:number; currentPlanVersion?:number; launchDate?:string;
  record?:Record<string,any>; budgetCount?:number;expenseCount?:number;
  budgets?:{currencyGroups:Record<string,any>[]};expenses?:{currencyGroups:Record<string,any>[]};
}
export interface ResearchBusinessPage extends PageResponse<ResearchBusinessRow> {summary:{recordCount:number;projectCount:number;quantity?:number}}
export const getResearchBusiness = async (params:{kind:string;keyword?:string;status?:string;projectId?:number;page:number;size:number}):Promise<ResearchBusinessPage> =>
  unwrapResponse(await request.get('/product-research/business',{params}));

export interface UnitCostRow {
  id:string; category:string; name:string; description?:string; mode:'unit'|'usage'|'divide';
  unitPrice:number|null; quantity?:number|null; lossPct?:number|null; allocationQty?:number|null;
  taxIncluded?:boolean|null; taxRate?:number|null; remark?:string; warning?:string; sourceCell?:string;
  estimated?:boolean; unitCost?:number|null; displayCost?:number|null; bomItemId?:number;
  sourceQuantity?:number|string|null;sourceOutputQty?:number|string|null;
}
export interface UnitCostSheet {
  title:string;currency:string;displayCurrency?:string;exchangeRate?:number|null;exchangeRateDate?:string|null;
  taxBasis:'source'|'net'|'gross';rows:UnitCostRow[];references:MaterialReference[];
  bomId?:number|null;routeId?:number|null;reason?:string;notes?:string;id?:number;status?:string;
  totals?:Record<string,number|null>;groups?:Record<string,number|null>;missing?:string[];warnings?:string[];
  complete?:boolean;confirmedAt?:string;confirmedBy?:string;voidReason?:string;bomCostSnapshotId?:number;
  sourceOutputQty?:number|string|null;
  basisType?:'bom'|'supplier_quote';quoteEventId?:number|null;quoteUnitNote?:string;
}

export async function getResearchReferencePage(query:PhoneObjectQuery):Promise<PhoneObjectPage<PhoneObjectOption>> {return unwrapResponse(await request.get('/product-research/reference-options/page',{params:query}))}
export async function getResearchReferencePhones(query:PhoneObjectQuery):Promise<PhoneObjectPage<ObjectPhoneGroup> & {unlinkedCount:number}> {return unwrapResponse(await request.get('/product-research/reference-options/phones',{params:query}))}
