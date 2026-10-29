<script setup lang="ts">
import { partnerError as requestError } from "@/utils/partnerError";
import { computed, defineAsyncComponent, nextTick, reactive, ref, watch } from "vue";
import {
  useRoute,
  useRouter,
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
} from "vue-router";
import { ElMessage, ElMessageBox, genFileId, type InputInstance, type UploadRawFile, type UploadUserFile } from "element-plus";
import { useEventListener, useResizeObserver } from "@vueuse/core";
import { ArrowDown } from "@element-plus/icons-vue";
import { useUserStore } from "@/stores/user";
import { useCollaborationWorkspace } from "@/stores/collaborationWorkspace";
import { createProductResearch } from "@/api/modules/product";
import {
  getProductResearch,
  deleteProductResearchDraft,
  deleteDevelopmentDraft,
  getProductResearchDetail,
  saveResearchDesignDraft,
  saveDevelopmentRound,
  researchAction,
  getResearchOwnerOptions,
  materialReferences,
  materialKey,
  type ProductResearchDetail,
  type ProductResearchRow,
  type DevelopmentRound,
  type DevelopmentMaterial,
  type WorkflowAction,
  type DesignDraftInput,
  type StageReadiness,
  type ResearchSupplierOption,
  type SupplierAssignment,
  type SupplierFeedbackInput,
  type SupplierFeedbackSummary,
} from "@/api/modules/productResearch";
import {
  getProjectTimeline,
  confirmTimelineNode,
  returnTimelineNode,
  publishProject,
  type TimelineDetailVO,
} from "@/api/modules/project";
import {
  getPhoneModelOptions,
} from "@/api/modules/phoneReference";
import { getMoldApplication, getResearchMoldApplicationContext, moldBusinessStatusNames } from "@/api/modules/moldApplications";
import MoldApplicationEditor from "@/views/mold-applications/components/MoldApplicationEditor.vue";
import { getPhoneCompatibleMolds } from "@/api/modules/phoneReference";
import {
  getFinanceRows,
  getFinanceSummary,
  type FinanceRow,
} from "@/api/modules/development";
import {
  uploadProjectAttachment,
  uploadAttachmentVersion,
  getAttachmentDetail,
  deleteAttachment,
  type AttachmentVO,
} from "@/api/modules/attachment";
import { useAttachmentViewer } from "@/composables/useAttachmentViewer";
import {
  FILE_CATEGORY_OPTIONS,
  ENGINEERING_UPLOAD_ACCEPT,
  MAX_UPLOAD_FILE_SIZE_BYTES,
  fileCategoryLabel,
} from "@/utils/file";
import { phoneMemberName } from "@/utils/phoneMembers";
import {
  PRODUCT_RESEARCH_STAGES,
  launchStage, legacyResearchStep,
} from "@/constants/productResearch";
import type { CompatiblePhone } from "@/types/molds";
import { researchBusinessRoute, researchSourceQuery } from "@/utils/researchNavigation";
import ResearchSnapshot from "./components/ResearchSnapshot.vue";
import DevelopmentReviewPanel from "./components/DevelopmentReviewPanel.vue";
import ResearchMaterialsPanel from "./components/ResearchMaterialsPanel.vue";
import SupplierFeedbackPanel from "./components/SupplierFeedbackPanel.vue";
import EngineeringBasisPanel from "./components/EngineeringBasisPanel.vue";
import DocumentPreview from "@/views/file/components/DocumentPreview.vue";
import StageReadinessChecklist from "./components/StageReadinessChecklist.vue";
import UnitCostSheet from "./components/UnitCostSheet.vue";
import SharedCompatiblePhoneSelector from "@/views/mold/components/SharedCompatiblePhoneSelector.vue";
const ProjectBomPanel = defineAsyncComponent(
  () => import("./components/ProjectBomPanel.vue"),
);
const ProjectProcessRoutePanel = defineAsyncComponent(
  () => import("./components/ProjectProcessRoutePanel.vue"),
);
const route = useRoute(),
  router = useRouter(),
  user = useUserStore(),
  collaboration = useCollaborationWorkspace(),
  viewer = useAttachmentViewer();
type Form = Record<string, any>;
type WorkflowRequestId=ReturnType<typeof crypto.randomUUID>;
const stage = computed(() => launchStage(route.query.step ?? (route.query.stage ? legacyResearchStep(route.query.stage) : detail.value?.workflow.revision?.stage || detail.value?.currentStepNo || 1))),
  id = computed(() => Number(route.query.productId) || 0);
const reviewPanel=ref<InstanceType<typeof DevelopmentReviewPanel>|null>(null);
const supplierFeedbackPanel=ref<InstanceType<typeof SupplierFeedbackPanel>|null>(null);
const supplierFeedbackContext=computed(()=>`${user.token}:${id.value}:${stage.value.step}`);
const supplierFeedbackLoadContext=ref('');
const feedbackProjectContext=computed(()=>`${user.token}:${id.value}`);
const readinessPanel=ref<InstanceType<typeof StageReadinessChecklist>|null>(null);
const validationAttempted=ref(false),readinessChecks=ref<StageReadiness['checks']>([]);
function fieldError(target:string){return validationAttempted.value?readinessChecks.value.filter(c=>!c.complete&&c.target===target).map(c=>c.message).join('；'):'';}
const designForm=ref<HTMLElement|null>(null);
const pageElement=ref<HTMLElement|null>(null);
const requirementsInput=ref<InputInstance>(),notesInput=ref<InputInstance>(),planNotesInput=ref<InputInstance>();
let editorWidth=-1;
useResizeObserver(designForm,([entry])=>{
  const width=entry?.contentRect.width??0;
  if(width===editorWidth)return;
  editorWidth=width;
  if(width>0)void nextTick(()=>{
    for(const input of [requirementsInput.value,notesInput.value,planNotesInput.value])input?.resizeTextarea();
    // Resize nested sample/review text as well when the expanded sidebar changes the available width.
    designForm.value?.querySelectorAll<HTMLTextAreaElement>('textarea').forEach(input=>{
      if(!input.scrollHeight)return;
      const border=input.offsetHeight-input.clientHeight;input.style.height='auto';input.style.height=`${input.scrollHeight+border}px`;
    });
  });
});
const engineeringOpen=ref(false),previewFile=ref<AttachmentVO|null>(null);
const materialsPanel=ref<InstanceType<typeof ResearchMaterialsPanel>>();
async function showMaterials(focusUpload=false){
  if(tab.value==='reviews'&&reviewPanel.value&&!(await reviewPanel.value.leave()))return;
  tab.value='work';await nextTick();
  const element=materialsPanel.value?.$el as HTMLElement|undefined;
  element?.scrollIntoView?.({block:'start',behavior:'smooth'});
  if(focusUpload)element?.querySelector<HTMLElement>('[data-readiness-focus="upload"]')?.focus({preventScroll:true});
}
const costSheet=ref<InstanceType<typeof UnitCostSheet>|null>(null);
async function changeCostType(value:any){if(costType.value==='unit'&&value!=='unit'&&costSheet.value&&!(await costSheet.value.canLeave()))return;costType.value=String(value);}
const rows = ref<ProductResearchRow[]>([]),
  total = ref(0),
  page = ref(1),
  state = ref("active"),
  keyword = ref(""),
  loading = ref(false),
  busy = ref(false),
  error = ref("");
const detail = ref<ProductResearchDetail | null>(null),
  timeline = ref<TimelineDetailVO | null>(null),
  tab = ref("work"),
  workspace = computed(() => String(route.meta.researchBusiness || "")),
  validationPane = ref("mold"),
  workKind = computed(()=>stage.value.step===1?"design":stage.value.step===2?"drawing":stage.value.step===3?"sample":stage.value.step===4?"confirmation":stage.value.step===5?"mold":stage.value.step===6?validationPane.value:stage.value.step===7?"pilot":"shipment"),
  extra = ref(false),
  engineering = ref("files"),
  keys = ref<string[]>([]);

const owners = ref<{ userId: number; name: string }[]>([]),
  suppliers = ref<ResearchSupplierOption[]>([]),
  canSelectSupplier = ref(false),
  engineeringReason = ref(''),
  supplierChangeContext=ref<{projectId:number;stage:number;token:string}|null>(null),
  phones = ref<CompatiblePhone[]>([]);
const stepForm = reactive<Form>({});
const profile = reactive<Form>({}),
  plan = reactive<Form>({}),
  sample = reactive<Form>({details:{phoneModelIds:[]}}),
  release = reactive<Form>({}),
  node = reactive<Form>({});
const designDraft = computed<DesignDraftInput>(() => {
  const references = materialReferences(keys.value);
  const planInput:Form={...plan,references,impactAcknowledged:true,...(engineeringReason.value.trim()?{reason:engineeringReason.value.trim()}:{})};
  if(plan.suppliersRestricted)delete planInput.supplierAssignments;
  delete planInput.suppliersRestricted;
  return {
    profile: {...profile,productName:profile.productName||'',model:profile.model||'',requirements:profile.requirements||'',targetCost:profile.targetCost??null,expectedDeliveryDate:profile.expectedDeliveryDate||null,phoneModelIds:plan.phoneModelIds||[],ownerUserId:profile.ownerUserId??null,references,version:version.value},
    plan: planInput,
  };
});
const moldSupplier = computed(()=>((plan.supplierAssignments||[]) as SupplierAssignment[]).find(a=>a.mode==='supplier'&&a.role==='mold'));
const nextMoldSupplierPending=computed(()=>!moldSupplier.value&&!!detail.value?.supplierFeedbackSummary?.nextSupplierPending);
function supplierFeedbackLabel(summary?:SupplierFeedbackSummary){
 if(summary?.restricted)return '反馈受限';
 const parts=[];
 if(summary?.cannotAcceptSupplierCount)parts.push(`${summary.cannotAcceptSupplierCount}家无法承接`);
 if(summary?.nextSupplierPending)parts.push('下一家待定');
 return parts.join(' · ')||(summary?.hasFeedback?'已记录反馈':'暂无有效反馈');
}
function chooseMoldSupplier(code:string){
 const existing=moldSupplier.value;
 const persisted=((workflow.value.planDraft as Form)?.supplierAssignments||(workflow.value.plan as Form)?.supplierAssignments||[]).some((a:SupplierAssignment)=>a.mode==='supplier'&&a.role==='mold'&&a.supplierCode===existing?.supplierCode);
 if(existing&&persisted&&code!==existing.supplierCode){openSupplierChange(existing);form.supplierCode=code;return;}
 const rows=(plan.supplierAssignments||[]) as SupplierAssignment[];
 plan.supplierAssignments=[...rows.filter(a=>a!==existing),...(code?[{mode:'supplier',supplierCode:code,role:'mold',scope:'本次开发',basis:''}]:[])];
}
function openSystemLogs(){void router.push({path:'/system/operation-log',query:{projectId:String(id.value)}});}
function projectCommand(command:string){
  if(command==='logs')openSystemLogs();
  else if(command==='materials')void showMaterials();
  else if(command==='delete')void deleteProjectDraft();
  else if(command==='promotion'||command==='presale')void openWorkspace(command);
}
function stepComplete(stepNo:number){
  return !!timeline.value?.nodes.find(node=>node.stepNo===stepNo)?.confirmed
    || timeline.value?.nodes.find(node=>node.stepNo===stepNo)?.status==='completed'
    || !!workflow.value[`stage${stepNo}Basis`];
}
const currentMold = ref<Form | null>(null),
  activeSampleId = ref<number | null>(null),
  snapshot = ref<unknown>(null),
  snapshotOpen = ref(false),
  snapshotTitle = ref("历史详情");
const stageDraft=computed(()=>stage.value.step===1?designDraft.value:{stage:stage.value.step,draft:{...stepForm,phoneModelIds:plan.phoneModelIds||[],references:materialReferences(keys.value)},...(stage.value.step===6?{sampleRoundId:activeSampleId.value||0}:{}),...(stage.value.step===7?{releaseDraft:{...release,references:materialReferences(keys.value)}}:{})});
watch(() => [id.value,stage.value.step,workspace.value],()=>{validationAttempted.value=false;readinessChecks.value=[];});
const newOpen = ref(false),
  newName = ref(""),
  taskOpen = ref(false),
  task = ref<WorkflowAction>("link"),
  form = reactive<Form>({}),
  uploadOpen = ref(false),
  uploadPrevious = ref<DevelopmentMaterial | null>(null),
  pendingFiles=ref<UploadUserFile[]>([]),
  uploadImagesOnly=ref(false),uploadError=ref(""),uploadedFileCount=ref(0),
  fileForm = reactive({ category: "drawing", versionNo: "V1", reason: "" });
const finances = ref<{ budgets: FinanceRow[]; expenses: FinanceRow[] }>({
    budgets: [],
    expenses: [],
  }),
  financeSummary = ref<Form>({}),
  financePage = ref(1),
  financeTotals = ref({budget:0,expense:0}),
  financeTotal = computed(()=>costType.value==='budget'?financeTotals.value.budget:financeTotals.value.expense),
  costType = ref("unit"),
  quotes = ref<Form[]>([]);
const moldChoices = ref<Form[]>([]),
  moldChoice = ref<number | null>(null),
  applicationChoice = ref<number | null>(null),
  noMoldReason = ref(""),
  reuseConfirmed=ref(false),reuseReason=ref("");
const moldApplicationEditor=ref<InstanceType<typeof MoldApplicationEditor>>();
let moldApplicationEpoch=0;
const publicationOpen = ref(false),
  publication = reactive({ reason: "", dueDate: "", risk: false });
const workflow = computed(() => detail.value?.workflow || { version: 0 }),
  version = computed(() => workflow.value.version || 0),
  actualStage = computed(
    () => workflow.value.revision?.stage || detail.value?.currentStepNo || 1,
  );
const blocked = computed(
  () =>
    !detail.value?.canWrite ||
    detail.value.status === "archived" ||
    ["frozen", "abandoned"].includes(detail.value?.lockStatus || ""),
);
const editable = computed(
  () =>
    !blocked.value &&
    stage.value.step === actualStage.value &&
    (detail.value?.status !== "released" || !!workflow.value.revision),
);
const parallelWritable = computed(() => !blocked.value),
  published = computed(() => detail.value?.status === "released"),
  sampleRows = computed(
    () => detail.value?.rounds.filter((r) => r.kind === "sample" && ([3,4].includes(stage.value.step) ? r.details?.samplePurpose==='prototype_3d' : r.details?.samplePurpose!=='prototype_3d')) || [],
  );
const selectedSample = computed(() =>
    sampleRows.value.find((r) => r.roundId === activeSampleId.value),
  ),
  sampleReadOnly = computed(
    () => !editable.value || selectedSample.value?.status === "confirmed",
  );
const molds = computed(() => ((detail.value as any)?.molds as Form[]) || []),
  applications = computed(
    () => ((detail.value as any)?.applications as Form[]) || [],
  );
const moldApplicationEntry=computed(()=>detail.value?.moldApplicationEntry);
const showMoldApplicationEntry=computed(()=>!workspace.value&&(!!moldApplicationEntry.value?.canCreate||user.hasPermission('collaboration:view')));
const canPrepareMoldApplication=computed(()=>!!moldApplicationEntry.value?.eligible&&!!moldApplicationEntry.value.canCreate&&!blocked.value&&(detail.value?.status!=='released'||!!workflow.value.revision));
const primaryMoldApplication=computed(()=>applications.value.find(application=>!['rejected','withdrawn','terminated'].includes(application.businessStatus||application.status))||applications.value[0]);
const canContinueMoldDraft=computed(()=>primaryMoldApplication.value?.status==='draft'&&primaryMoldApplication.value.canEditDraft===true&&moldApplicationEntry.value?.canCreate);
const moldApplicationEntryLabel=computed(()=>primaryMoldApplication.value?(canContinueMoldDraft.value?'继续LEMOS草稿':'查看LEMOS审批进度'):'发起LEMOS开模申请');
const confirmedPlan = computed(() => (workflow.value.plan as Form) || {}),
  effectivePhones = computed<number[]>(
    () => confirmedPlan.value.phoneModelIds || profile.phoneModelIds || [],
  );
const currentMaterials = computed(() => detail.value?.materials || []),
  adopted = computed(() =>
    currentMaterials.value.filter((m) => keys.value.includes(materialKey(m))),
  );
const adoptedSample = computed(() => {
  const snapshot=(workflow.value.validationBasis as Form)?.sample;
  return snapshot?.roundNo ? snapshot : detail.value?.rounds.find(r=>r.roundId===Number(snapshot?.roundId||workflow.value.validationSampleId||workflow.value.selectedRoundId));
});
const canMoney = computed(() => user.hasPermission("finance:read")),
  canFinanceWrite = computed(
    () => canMoney.value && user.hasPermission("finance:write"),
  );
const promotionRows = computed(
    () => (workflow.value.promotions as Form[]) || [],
  ),
  presaleRows = computed(() => (workflow.value.presales as Form[]) || []),
  presaleQty = computed(() =>
    presaleRows.value
      .filter((r) => r.status !== "cancelled")
      .reduce((sum, r) => sum + Number(r.quantity), 0),
  );
const canMaintainPublished = computed(
  () =>
    published.value &&
    !blocked.value &&
    stage.value.step === actualStage.value &&
    actualStage.value === 8,
);
const currentPackage = computed(() => detail.value?.release),
  materialsEditable = computed(
    () => editable.value && stage.value.step!==4 && (![3,6].includes(stage.value.step) || !sampleReadOnly.value),
  );
function updateMaterialKeys(selected:string[]) {
  // The stage's adopted references remain the sole editable BOM/process selection.
  keys.value=selected.filter((key,index)=>!['bom','process'].includes(key.split(':')[0]!)||!selected.slice(index+1).some(next=>next.split(':')[0]===key.split(':')[0]));
}
async function confirmEngineering(changed:boolean){
  await action(async()=>{
    if(!detail.value?.canConfirmEngineering)throw Error('当前无权确认工程方案');
    if(detail.value.engineering?.confirmed&&changed&&!engineeringReason.value.trim())throw Error('请填写工程方案变更原因');
    if(stage.value.step!==6||!sampleReadOnly.value)await save();
    await command('engineering_confirm',{stage:stage.value.step,reason:engineeringReason.value,...(stage.value.step===6?{sampleRoundId:activeSampleId.value||undefined}:{})});
    engineeringReason.value='';
    ElMessage.success('供应商与工程方案已确认，相关模块已同步更新');
  });
}
function openSupplierMaterials(){
  const destination=router.resolve({path:'/suppliers',query:{projectId:String(id.value)}});
  window.open(destination.href,'_blank','noopener,noreferrer');
}
function openProductIdentity(){
  if(!detail.value?.canMaintainProductIdentity)return;
  const destination=router.resolve({path:`/products/${id.value}/edit`,query:{source:'research',step:stage.value.key}});
  window.open(destination.href,'_blank','noopener,noreferrer');
}
async function locateEngineeringGap(target:string){
  if(target==='product-identity'){openProductIdentity();return;}
  if(target.startsWith('engineering')){
    if(busy.value)return;
    if(tab.value==='reviews'&&reviewPanel.value&&!(await reviewPanel.value.leave()))return;
    if(!(await beforeStageTabLeave('work')))return;
    tab.value='work';
    await nextTick();
    designForm.value?.querySelector<HTMLElement>('[aria-label="供应商与工程方案"]')?.scrollIntoView?.({block:'start',behavior:'smooth'});
    if(target==='engineering-bom')await openEngineering('bom');
    else if(target==='engineering-process')await openEngineering('process');
    return;
  }
  if(target.includes('bom')){await openEngineering('bom');return;}
  if(target.includes('process')){await openEngineering('process');return;}
  await locateStageGap(target);
}
const nodes: Record<string, string> = {
  making: "模具制作完成",
  testing: "验模",
  rework: "改模完成",
  ready: "验模通过",
};
const eventNames: Record<string, string> = {
  draft_saved: "保存需求草稿",design_draft:"保存立项设计草稿",confirm_design_basis:"确认需求与计划",phase_model_migrated:"阶段调整历史",
  requirement_confirmed: "确认需求",
  plan_draft: "保存计划草稿",
  engineering_confirm: "确认供应商与工程方案",
  supplier_change: "更换供应商",
  supplier_feedback: "供应商反馈",
  plan_confirmed: "确认计划",
  handover: "阶段交接",
  returned: "退回前序",
  revision_return: "修订退回",
  mold_node: "模具节点办理",
  mold_restart: "重新验模",
  mold_link: "关联模具／申请",
  no_mold: "确认无需开模",
  release_draft: "保存定型清单",
  finalize: "确认定型",
  published: "形成正式包",
  launch: "登记实际上市",
  promotion: "推广记录",
  presale: "预售记录",
  cost: "成本登记",
  quote: "报价版本",
  link: "参考链接",
  supplement: "补齐资料",
  gap_plan: "调整补齐计划",
  transfer: "移交",
  receipt: "接收",
  start_revision: "发起修订",
  launch_draft: "保存阶段草稿",
  review_setup: "维护测试及试产要求",review_save:"评审记录",review_delete:"删除评审草稿",review_void:"作废评审",round_void:"作废开发记录",unit_cost_save:"保存单件成本草稿",unit_cost_confirm:"确认单件成本",unit_cost_delete:"删除成本草稿",unit_cost_void:"作废成本版本",unit_cost_from_bom:"从BOM带入核算",
};
const channelNames: Record<string, string> = {
  ecommerce: "电商矩阵",
  mcn: "合作 MCN",
  wholesale: "线下批发",
  chain: "线下连锁",
};
const statusNames: Record<string, string> = {
  pending: "待准备",
  working: "进行中",
  done: "已完成",
  cancelled: "已取消",
  reserved: "预订",
  confirmed: "已确认",
  fulfilled: "已交付",
};
const missing = computed(() => {
  const direct = confirmedPlan.value.method === "direct";
  const need = direct
    ? [
        ["customer_confirm", "确认样／签样"],
        ["testing", "验证报告"],
      ]
    : [
        ["bom", "BOM"],
        ["process", "工艺"],
        ["drawing", "图纸"],
        ["customer_confirm", "确认样／签样"],
        ["testing", "验证报告"],
        ["production_docs", "SOP／SIP"],
      ];
  return need
    .filter(
      ([type]) =>
        !adopted.value.some(
          (m) =>
            m.type === type ||
            m.category === type ||
            (type === "production_docs" &&
              ["sop", "sip"].includes(m.category || "")),
        ),
    )
    .map(([, name]) => name);
});
const dirty = ref(false);
let queuedRoute: {path:string;query:Record<string,string>} | null = null;
let hydrating = false,
  generation = 0;
const clone = <T,>(v: T): T => JSON.parse(JSON.stringify(v));
const drafts = new Map<string, Form>();
const draftKey = (project = id.value, step: number = stage.value.step) =>
  `${user.token}:${project}:${step}`;
watch(
  [profile, plan, sample, release, keys, node, noMoldReason, stepForm],
  () => {
    if (!hydrating) dirty.value = true;
  },
  { deep: true, flush: "sync" },
);
function inspect(value: unknown, title = "历史详情") {
  snapshot.value = value;
  snapshotTitle.value = title;
  snapshotOpen.value = true;
}
async function action(fn: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await fn();
  } catch (e) {
    if (e !== "cancel" && e !== "close") {
      error.value = requestError(e);
      ElMessage.error(error.value);
    }
  } finally {
    busy.value = false;
    if(queuedRoute){const destination=queuedRoute;queuedRoute=null;void router.push(destination);}
  }
}
function routeTo(step:number,project=id.value,focus?:'supplier-feedback'){
 const query:Record<string,string>={step:launchStage(step).key};if(project)query.productId=String(project);
 if(focus)query.focus=focus;
 const destination={path:'/product-research',query};if(busy.value)queuedRoute=destination;else void router.push(destination);
}
function clearAssign(target: Form, data: Form) {
  Object.keys(target).forEach((k) => delete target[k]);
  Object.assign(target, clone(data));
}
function inheritReferences() {
  if(stage.value.step===8)return [];
  if(stage.value.step===4)return (detail.value?.rounds.find(r=>r.roundId===workflow.value.prototypeRoundId)?.references||[]) as DevelopmentMaterial[];
  if([2,3].includes(stage.value.step))return ((workflow.value.stage2Basis as Form)?.references||profile.references||[]) as DevelopmentMaterial[];
  if(stage.value.step===7){
    const gate=detail.value?.reviewGates?.find(r=>r.gate==='pilot'&&r.status==='passed')||detail.value?.reviewGates?.find(r=>r.gate==='reliability'&&r.status==='passed');
    const basis=detail.value?.rounds.find(r=>r.roundId===gate?.recordId)||(workflow.value.validationBasis as Form)?.reliability;
    if(basis?.references)return basis.references as DevelopmentMaterial[];
  }
  const selected=detail.value?.rounds.find(r=>r.roundId===(workflow.value.validationSampleId||workflow.value.selectedRoundId));
  const design=((workflow.value.reviewRecords as Form[])||[]).slice().reverse().find(r=>r.gate==='design'&&r.status==='confirmed'&&r.result==='passed'&&!r.voidReason&&!r.deleted);
  return (stage.value.step===6&&selected?.references?.length?selected.references:design?.references||(workflow.value.requirement as Form)?.references||[]) as DevelopmentMaterial[];
}
async function switchValidationPane(value:any){await action(async()=>{if(dirty.value&&!sampleReadOnly.value)await save();validationPane.value=String(value);currentMold.value=null;keys.value=(validationPane.value==='sample'&&selectedSample.value?selectedSample.value.references:inheritReferences()).map(materialKey);dirty.value=false;});}
async function beforeStageTabLeave(next:string|number){
 if(next!=='reviews'||!editable.value||!dirty.value)return true;
 if(stage.value.step===6&&sampleReadOnly.value)return true;
 if(busy.value)return false;let saved=false;await action(async()=>{await save();saved=true;});return saved;
}

async function locateStageGap(target:string){
  if(target.startsWith('engineering')){await locateEngineeringGap(target);return;}
  if(stage.value.step!==1){await locateLaterGap(target);return;}
  const next=['material-basis','design-review'].includes(target)?'reviews':'work';
  if(!(await beforeStageTabLeave(next)))return;
  tab.value=next;
  if(target==='drawings'){await showMaterials(true);return;}
  await nextTick();
  if(next==='reviews'){await reviewPanel.value?.openGap("design",target);return;}
  const element=designForm.value?.querySelector<HTMLElement>(`[data-readiness-target="${target}"]`);
  element?.scrollIntoView({block:'center',behavior:'smooth'});
  const focusable='input:not([type="hidden"]),textarea,button,[tabindex="0"]';
  (element?.matches(focusable)?element:element?.querySelector<HTMLElement>(focusable))?.focus({preventScroll:true});
}

async function locateLaterGap(target:string){
  if(busy.value)return;
  if(target==='previous-stage'){await returnPrevious();return;}
  if(target.startsWith('application:')){await router.push({path:'/mold-applications',query:{applicationId:target.split(':')[1],projectId:id.value}});return;}
  const inherited=(target==='mold-links'&&stage.value.step>=6)||(stage.value.step===7&&['sample','no-mold'].includes(target));
  if(inherited){
    ElMessage.info('这项资料属于前序阶段，请通过原“退回上一阶段”入口填写原因后补齐；原记录继续保留。');
    document.querySelector<HTMLElement>('[data-readiness-target="previous-stage"]')?.focus();return;
  }
  if(target==='mold-coverage'){
    const mold=molds.value[0];if(mold)await router.push({path:'/molds',query:{moldSetId:mold.moldSetId,tab:'master',projectId:id.value}});return;
  }
  if(target.startsWith('shipment:')){
    const moldId=Number(target.split(':')[1]),mold=molds.value.find(m=>m.moldSetId===moldId);
    if(!mold?.actualShipDate){await openSource('/mold-schedules',{projectId:id.value,moldSetId:moldId});return;}
    tab.value='work';await nextTick();const field=designForm.value?.querySelector<HTMLElement>('[data-readiness-target="shipment"]');field?.scrollIntoView({block:'center'});field?.querySelector<HTMLElement>('input,button')?.focus();ElMessage.info('实际发模日期已登记，请选择本批模具套并确认本批发模');return;
  }
  if(target==='review:design'&&![4,6].includes(stage.value.step)){await returnPrevious(stage.value.step===5?4:6);return;}
  if(stage.value.step===4&&target.startsWith('review:')){tab.value='work';await nextTick();await reviewPanel.value?.openGap('design');return;}
  if(target.startsWith('review:')){
    if(!(await beforeStageTabLeave('reviews')))return;
    tab.value='reviews';await nextTick();
    const [,gate,moldId]=target.split(':');await reviewPanel.value?.openGap(gate!,undefined,moldId?Number(moldId):undefined);return;
  }
  if(reviewPanel.value&&!(await reviewPanel.value.leave()))return;
  tab.value='work';
  if(stage.value.step===6){
    const pane=target==='sample'?'sample':'mold';
    if(validationPane.value!==pane){await switchValidationPane(pane);if(validationPane.value!==pane)return;}
  }
  if(target==='drawings'){await showMaterials(true);return;}
  if(target==='release-cost'&&!detail.value?.unitCostOptions?.some(v=>!v.voidReason)){
    if(canMoney.value){openWorkspace('cost');return;}
    ElMessage.info('请具备成本权限的同事在项目成本中确认本方案成本版本，再在此选择。');
  }
  if(target.startsWith('mold:')){
    const mold=molds.value.find(m=>m.moldSetId===Number(target.split(':')[1]));
    if(mold){
      if(mold.schedulePaused){await router.push({path:'/molds',query:{moldSetId:mold.moldSetId,tab:'sets'}});return;}
      if(stage.value.step===7){inspect(mold,'已采用模具与验模依据；修改请退回开模步骤');return;}
      if(!detail.value?.canScheduleWrite){inspect(mold,'模具进度与依据');return;}
      const node=mold.development?.node||'making';
      if(stage.value.step===6&&['making','testing','rework'].includes(node)){if(dirty.value){let saved=false;await action(async()=>{await save();saved=true;});if(!saved)return;}openNode(mold);}
      else if(stage.value.step===6&&node==='ready'){inspect(mold,'验模依据；需重新核对时使用“重新验模”');}
    }
  }
  await nextTick();
  const fallback=target==='release-scope'?'.basis-summary':target==='sample'?'.toolbar':'.mold-row';
  const element=designForm.value?.querySelector<HTMLElement>(`[data-readiness-target="${target}"]`)||designForm.value?.querySelector<HTMLElement>(fallback);
  element?.scrollIntoView({block:'center',behavior:'smooth'});element?.querySelector<HTMLElement>('input:not([type="hidden"]),textarea,button')?.focus({preventScroll:true});
}

function newSample(previous?: DevelopmentRound) {
  hydrating = true;
  activeSampleId.value = null;
  clearAssign(sample, {
    kind: "sample",
    recordDate: "",
    title: "",
    result: "pending",
    issues: "",
    changes: "",
    retestResult: "",
    details: {
      phoneModelIds: effectivePhones.value,
      requirementVersion: workflow.value.requirementVersion,
      samplePurpose: stage.value.step===3?"prototype_3d":"mold_trial",
      makerName: previous?.details?.makerName || "",
      supplierCode: previous?.details?.supplierCode || "",
      designSummary: previous?.details?.designSummary || (workflow.value.stageDrafts as Form)?.[2]?.notes || ((workflow.value.reviewRecords as Form[])||[]).find(r=>r.id===detail.value?.reviewGates?.find(g=>g.gate==='design'&&g.status==='passed')?.recordId)?.summary || "",
      actualResult: "",
      previousRoundId: previous?.roundId || null,
    },
  });
  const design=((workflow.value.reviewRecords as Form[])||[]).find(r=>r.id===detail.value?.reviewGates?.find(g=>g.gate==='design'&&g.status==='passed')?.recordId);
  keys.value = (design?.references || previous?.references || inheritReferences()).map(materialKey);
  hydrating = false;
  dirty.value = true;
}
function openSample(row: DevelopmentRound) {
  hydrating = true;
  activeSampleId.value = row.roundId;
  clearAssign(sample, row);
  keys.value = row.references.map(materialKey);
  hydrating = false;
  dirty.value = false;
}
function openNode(mold: Form) {
  const dev = mold.development || {};
  if(stage.value.step!==6){inspect(mold,"请在开模步骤办理制作、试模与改模");return;}
  hydrating = true;
  currentMold.value = mold;
  clearAssign(node, {
    node: dev.node || "making",
    actualDate: "",
    result: "",
    note: "",
    ...(dev.draft || {}),
    nodeVersion: dev.version || 0,
  });
  const design=((workflow.value.reviewRecords as Form[])||[]).find(r=>r.id===detail.value?.reviewGates?.find(g=>g.gate==='design'&&g.status==='passed')?.recordId);
  keys.value = (dev.draft?.basis?.references || (dev.node==='rework'?design?.references:null) || inheritReferences()).map(materialKey);
  tab.value = "work";
  hydrating = false;
  dirty.value = false;
}
function hydrate() {
  if (!detail.value) return;
  hydrating = true;
  extra.value = false;
  currentMold.value = null;
  clearAssign(profile, {
    productName: detail.value.productName,
    model: detail.value.model,
    requirements: detail.value.requirements,
    ownerUserId: detail.value.ownerUserId,
    phoneModelIds: [],
    pendingReason: "",
    targetCost: detail.value.targetCost,
    currency: "CNY",
    costUnit: "每件",
    expectedDeliveryDate: detail.value.expectedDeliveryDate,
    ...workflow.value.profileDraft,
  });
  clearAssign(plan, {
    phoneModelIds: profile.phoneModelIds || [],
    launchDate: "",
    pilotDate:"",
    executionMode: "outsourced",
    method: "supplier",
    needsMold: true,
    ...((workflow.value.plan as Form) || {}),
    ...((workflow.value.planDraft as Form) || {}),
  });
  plan.supplierAssignments=clone(plan.supplierAssignments||[]);
  engineeringReason.value='';
  clearAssign(release, {
    phoneModelIds: effectivePhones.value,
    confirmationDate: "",
    sampleNote: "",
    sampleLocation: "",
    ...(stage.value.step===7?defaultReleaseBasis():{}),
    ...workflow.value.releaseDraft,
  });
  clearAssign(stepForm,(workflow.value.stageDrafts as Form)?.[stage.value.step]||{});
  keys.value=(stage.value.step===1?profile.references||[]:stage.value.step===7?release.references||inheritReferences():(workflow.value.stageDrafts as Form)?.[stage.value.step]?.references||inheritReferences()).map(materialKey);
  noMoldReason.value=(workflow.value.noMold as Form)?.reason||(workflow.value.stageDrafts as Form)?.[2]?.reason||"";
  if ([3,6].includes(stage.value.step)) {
    const recent =
      sampleRows.value.find((r) => r.status === "draft") || sampleRows.value[0];
    if (recent) openSample(recent);
    else newSample();
  }
  const saved = drafts.get(draftKey());
  if (saved) {
    if (saved.version === version.value) {
      clearAssign(profile, saved.profile);
      clearAssign(plan, saved.plan);
      clearAssign(sample, saved.sample);
      clearAssign(release, saved.release);
      clearAssign(stepForm,saved.stepForm||{});
      keys.value = saved.keys;
      activeSampleId.value = saved.activeSampleId;
      noMoldReason.value = saved.noMoldReason;
    } else {
      inspect(saved, "未提交输入：服务器已有新版本，请核对后重新填写");
      drafts.delete(draftKey());
    }
  }
  hydrating = false;
  dirty.value = !!saved;
}
function remember(project: number, step: number) {
  if (detail.value && dirty.value)
    drafts.set(
      draftKey(project, step),
      clone({
        version: version.value,
        stepForm,
        profile,
        plan,
        sample,
        release,
        keys: keys.value,
        activeSampleId: activeSampleId.value,
        noMoldReason: noMoldReason.value,
      }),
    );
}
async function refresh(hydrateForm = false) {
  const project=id.value,token=user.token;
  const [d, t] = await Promise.all([
    getProductResearchDetail(project),
    getProjectTimeline(project),
  ]);
  if(project!==id.value||token!==user.token)return;
  if(d.productId!==project)throw Error('返回的项目资料不匹配，请重新载入');
  detail.value = d;
  timeline.value = t;
  if (hydrateForm) hydrate();
}
async function load() {
  const g = ++generation;
  const project=id.value,token=user.token;
  supplierFeedbackLoadContext.value='';
  loading.value = true;
  error.value = "";
  try {
    if (id.value) {
      const [d, t] = await Promise.all([
        getProductResearchDetail(id.value),
        getProjectTimeline(id.value),
      ]);
      if (g !== generation||project!==id.value||token!==user.token) return;
      if(d.productId!==project)throw Error('返回的项目资料不匹配，请重新载入');
      detail.value = d;
      timeline.value = t;
      if(!route.query.step&&!route.query.stage){await router.replace({path:route.path,query:{...route.query,step:launchStage(d.workflow.revision?.stage||d.currentStepNo||1).key}});}
      hydrate();
      await loadSelectedPhones();
      if (workspace.value === "cost" || workspace.value === "promotion") await loadFinance();
      if (["bom","process"].includes(String(route.query.materials))) { engineering.value=String(route.query.materials);engineeringOpen.value=true; }
      if(g===generation&&project===id.value&&token===user.token)supplierFeedbackLoadContext.value=feedbackProjectContext.value;
    } else {
      const r = await getProductResearch({
        keyword: keyword.value,
        state: state.value,
        page: page.value,
        size: 50,
        flow: "launch_v1",
      });
      if (g !== generation) return;
      rows.value = r.content;
      total.value = r.totalElements;
      detail.value = null;
    }
  } catch (e) {
    if(g===generation&&project===id.value&&token===user.token)error.value = e instanceof Error ? e.message : "载入失败";
  } finally {
    if (g === generation) loading.value = false;
  }
}
let focusingSupplierFeedback=false,handledSupplierFeedback='';
async function focusSupplierFeedback(){
  const project=id.value,token=user.token,g=generation;
  const current=()=>g===generation&&project===id.value&&token===user.token&&detail.value?.productId===project&&supplierFeedbackLoadContext.value===feedbackProjectContext.value;
  if(focusingSupplierFeedback||handledSupplierFeedback===supplierFeedbackContext.value||route.query.focus!=='supplier-feedback'||!project||workspace.value||loading.value||busy.value||error.value||!current())return;
  focusingSupplierFeedback=true;
  try{
    if(tab.value==='reviews'&&reviewPanel.value&&!(await reviewPanel.value.leave()))return;
    tab.value='work';await nextTick();
    if(!current())return;
    const element=pageElement.value?.querySelector<HTMLElement>('[aria-label="供应商反馈"]'),container=element?.closest<HTMLElement>('.development-workspace');
    if(!element||!container)return;
    element.setAttribute('tabindex','-1');
    container.scrollTop=Math.max(0,container.scrollTop+element.getBoundingClientRect().top-container.getBoundingClientRect().top);
    element.focus({preventScroll:true});
    handledSupplierFeedback=supplierFeedbackContext.value;
  }finally{focusingSupplierFeedback=false;}
}
watch(()=>[route.query.focus,loading.value,busy.value,supplierFeedbackLoadContext.value,user.token],()=>{if(route.query.focus!=='supplier-feedback')handledSupplierFeedback='';void focusSupplierFeedback();},{immediate:true,flush:'post'});
watch(
  [() => id.value, () => stage.value.step, () => workspace.value],
  (_, old) => {
    handledSupplierFeedback='';
    if (old?.[0]) remember(Number(old[0]), Number(old[1]));
    tab.value = "work";
    dirty.value = false;
    void load();
  },
  { immediate: true },
);
watch([state, page], () => {
  if (!id.value) void load();
});
onBeforeRouteLeave(async () => {
  if (busy.value || !(await canLeaveSupplierEntries())) return false;
  if (dirty.value) {
    try {
      await ElMessageBox.confirm("当前输入尚未保存，离开页面？", "未保存草稿", {
        confirmButtonText: "离开",
        cancelButtonText: "继续填写",
      });
    } catch {
      return false;
    }
  }
  return true;
});
onBeforeRouteUpdate(async () => !busy.value && await canLeaveSupplierEntries());
async function loadOwnerOptions(){
  try {
    const o=await getResearchOwnerOptions();
    owners.value = o.owners;
    suppliers.value = o.suppliers || [];
    canSelectSupplier.value=o.canSelectSupplier??user.hasPermission('supplier:view');
  }catch{ /* The original draft remains available when reference loading fails. */ }
}
void loadOwnerOptions();
watch(()=>collaboration.revision,()=>{if(id.value&&!busy.value)void refresh().catch(()=>{});else if(!id.value)void load();});
useEventListener(window,'focus',()=>{if(id.value&&!busy.value){void loadOwnerOptions();void refresh().catch(()=>{});}});
function rememberPhones(rows: CompatiblePhone[]) {
  phones.value = [...new Map([...phones.value, ...rows].map(p => [p.phoneModelId, p])).values()];
}
async function loadSelectedPhones() {
  const selected = [...new Set<number>([...(profile.phoneModelIds || []), ...(plan.phoneModelIds || []), ...effectivePhones.value])];
  phones.value = selected.length ? await getPhoneModelOptions(selected) : [];
}
function phoneLabel(value: number | null | undefined) {
  if (value == null) return "标准手机待补充";
  const p = phones.value.find((p) => p.phoneModelId === value);
  return p ? phoneMemberName(p) : `机型 #${value}`;
}
async function command(name: WorkflowAction, data: object, requestId?:WorkflowRequestId) {
  const project=id.value,token=user.token,step=stage.value.step;
  const updated=await (requestId?researchAction(project,name,version.value,data,requestId):researchAction(project,name,version.value,data));
  if(project!==id.value||token!==user.token||step!==stage.value.step)throw Error('项目、阶段或登录身份已变化，请重新载入核对保存结果');
  detail.value=updated;
  if(['engineering_confirm','supplier_change','supplier_feedback','launch'].includes(name))collaboration.changed();
}
async function create() {
  await action(async () => {
    if (!newName.value.trim()) throw Error("请填写产品名称");
    const result = await createProductResearch({
      productName: newName.value.trim(),
      productType: "product_line",
      versionNo: "V1",
      createdBy: user.profile?.userName || "system",
    });
    newOpen.value = false;
    newName.value = "";
    routeTo(1, result.productId);
  });
}
async function deleteProjectDraft(row?:ProductResearchRow) {
  await action(async()=>{
    const target=row?await getProductResearchDetail(row.productId):detail.value!;
    await ElMessageBox.confirm(`删除“${target.productName}”未确认立项的草稿？已有下游记录的项目将被阻止删除。`,"删除新品草稿");
    await deleteProductResearchDraft(target.productId,target.workflow.version||0);
    dirty.value=false;
    if(row)await load();else routeTo(1,0);
    ElMessage.success("新品草稿已删除");
  });
}
async function deleteSampleDraft(){
  if(!activeSampleId.value)return;
  await action(async()=>{await ElMessageBox.confirm("删除这轮未确认的样品草稿？","删除样品草稿");await deleteDevelopmentDraft(id.value,activeSampleId.value!,version.value);activeSampleId.value=null;dirty.value=false;await refresh(true);});
}
async function voidSample(){await action(async()=>{const response=await ElMessageBox.prompt('填写作废原因。原收样、T0和测试历史保留，后续需采用有效样品。','作废样品',{inputValidator:v=>!!v?.trim()||'请填写原因'});await command('round_void',{roundId:activeSampleId.value,reason:response.value});dirty.value=false;await refresh(true);});}
function defaultReleaseBasis(){
 const pilot=detail.value?.rounds.find(r=>r.roundId===detail.value?.reviewGates?.find(g=>g.gate==='pilot'&&g.status==='passed')?.recordId);
 const refs=pilot?.references||inheritReferences();
 const costs=detail.value?.unitCostOptions?.filter(v=>!v.voidReason&&((confirmedPlan.value.method==='direct'&&v.basisType==='supplier_quote')||refs.some(r=>r.type==='bom'&&r.id===v.bomId)))||[];
 return {pilotRoundId:pilot?.roundId||null,unitCostVersionId:costs.length===1?costs[0]?.id:null,references:refs};
}
function releasePilotChanged(value:number){
 const pilot=detail.value?.rounds.find(r=>r.roundId===value);if(!pilot)return;
 release.phoneModelIds=clone(pilot.details?.phoneModelIds||effectivePhones.value);keys.value=pilot.references.map(materialKey);dirty.value=true;
}
function childUpdated(value:ProductResearchDetail){
 const previousDesign=detail.value?.reviewGates?.find(g=>g.gate==='design'&&g.status==='passed')?.recordId;
 detail.value=value;
 const currentDesign=value.reviewGates?.find(g=>g.gate==='design'&&g.status==='passed')?.recordId;
 if(stage.value.step===1&&currentDesign&&currentDesign!==previousDesign){hydrating=true;keys.value=(value.workflow.profileDraft?.references||[]).map(materialKey);hydrating=false;}
 if(stage.value.step===7&&!workflow.value.releaseDraft&&!dirty.value){hydrating=true;Object.assign(release,defaultReleaseBasis());keys.value=(release.references||[]).map(materialKey);hydrating=false;}
}
async function save(confirmRound = false) {
 const refs=materialReferences(keys.value);
 if(workKind.value==='design')detail.value=await saveResearchDesignDraft(id.value,designDraft.value.profile,designDraft.value.plan);
 else if(['drawing','confirmation','shipment'].includes(workKind.value)){
  if(stage.value.step===2)await command('plan_draft',{...plan,references:refs,impactAcknowledged:true});
  await command('launch_draft',{...stepForm,stage:stage.value.step,phoneModelIds:plan.phoneModelIds||[],references:refs});
 }else if(workKind.value==='sample'){
  const saved=await saveDevelopmentRound(id.value,activeSampleId.value,{...sample,kind:'sample',recordDate:sample.recordDate||null,title:sample.title||'样品',result:stage.value.step===3?'pending':sample.result||'pending',issues:sample.issues||'',changes:sample.changes||'',retestResult:sample.retestResult||'',confirm:confirmRound,details:sample.details,references:refs,version:version.value,requestId:crypto.randomUUID()} as any);
  activeSampleId.value=saved.roundId;await refresh();if(confirmRound)openSample(saved);
 }else if(workKind.value==='mold'){
  if(currentMold.value){await command('mold_node',{...node,moldSetId:currentMold.value.moldSetId,references:refs,confirm:confirmRound});const row=molds.value.find(m=>m.moldSetId===currentMold.value?.moldSetId);if(row){if(workKind.value==='mold'&&row.development?.node!=='making')currentMold.value=null;else openNode(row);}}
  else {if(stage.value.step===5)await command('plan_draft',{...plan,references:refs,impactAcknowledged:true});await command('launch_draft',{...stepForm,stage:stage.value.step,reason:noMoldReason.value,references:refs});}
 }else {await command('plan_draft',{...plan,references:refs,impactAcknowledged:true,...(engineeringReason.value?{reason:engineeringReason.value}:{})});await command('release_draft',{...release,references:refs});}
 dirty.value=false;drafts.delete(draftKey());
}

async function saveClick(confirm = false) {
  await action(async () => {
    await save(confirm);
    ElMessage.success(
      confirm ? (stage.value.step===3?"已登记收样，待负责人确认":"已确认，原依据与结果保留") : "本阶段办理草稿已保存",
    );
  });
}
async function completionErrors(handover=false) {
  const errors: string[] = [];
  if (readinessPanel.value) {
    validationAttempted.value=true;
    const checked=await readinessPanel.value.checkNow(true);
    const reviewSteps=new Set(['DESIGN_BASIS','MATERIAL_BASIS','DESIGN_DRAWING','DESIGN_REVIEW']);
    errors.push(...checked.checks.filter(item=>!item.complete&&(stage.value.step!==1||handover||!reviewSteps.has(item.code))).map(item=>item.message));
  }
  return errors;
}
async function confirmShipmentBatch(){await action(async()=>{await save();await command('shipment_confirm',{});dirty.value=false;ElMessage.success('本批发模已确认，未发套继续保留待办');});}
async function complete() {
 await action(async()=>{
  if(currentMold.value)throw Error('请先完成当前模具节点并返回列表');
  if(workKind.value==='sample'){if(!sampleReadOnly.value)await save(true);}else if(stage.value.step!==4)await save();
  const errors=await completionErrors(true);if(errors.length){await ElMessageBox.alert('请补齐：'+errors.join('、'),'本步缺项');return;}
  const next=PRODUCT_RESEARCH_STAGES.find(s=>s.step===stage.value.step+1);
  await ElMessageBox.confirm(stage.value.step===1?'负责人确认本次立项，开始画3D产品图？':next?`完成${stage.value.name}，进入${next.name}？`:'确认本批模具已经实际发出？','确认本步完成');
  if(workflow.value.revision)await command('revision_handover',{stage:stage.value.step});
  else await confirmTimelineNode(id.value,timeline.value!.nodes.find(n=>n.stepNo===stage.value.step)!.nodeKey,`完成${stage.value.name}`,version.value);
  dirty.value=false;drafts.delete(draftKey());
  if(next)routeTo(next.step);else{await refresh();ElMessage.success('本批已发模');}
 });
}
async function returnPrevious(targetStep?:number) {
  await action(async () => {
    const target = Number.isInteger(targetStep)?targetStep!:actualStage.value - 1;
    if(reviewPanel.value&&!(await reviewPanel.value.leave()))return;
    if(dirty.value&&stage.value.step!==4&&!(workKind.value==='sample'&&sampleReadOnly.value))await save();
    const response = await ElMessageBox.prompt(
      "填写原因。原阶段记录和失败事实继续保留。",
      `退回${launchStage(target).name}`,
      {
        confirmButtonText: "确认",
        cancelButtonText: "取消",
        inputValidator: (v) => !!v?.trim() || "请填写原因",
      },
    );
    const source=detail.value?.rounds.find(r=>r.status==='confirmed'&&!r.voided&&r.result==='failed'&&(actualStage.value===7?r.kind==='pilot':actualStage.value===6?['sample','test'].includes(r.kind):false));
    if (workflow.value.revision)
      await command("revision_return", {
        stage: target,
        reason: response.value,
        sourceRoundId:source?.roundId,
      });
    else
      await returnTimelineNode(
        id.value,
        timeline.value!.currentStepCode!,
        response.value,
        true,
        source?.roundId,
        version.value,
        target,
      );
    dirty.value = false;
    routeTo(target);
  });
}
async function openWorkspace(value: string) {
  await router.push(researchBusinessRoute(value, id.value, stage.value.step));
}
function ownedCost(row:Form) {return Number(row.productId)===id.value || Number(row.productLineId)===id.value;}
function openSource(path:string, query:Form={}) {
  void router.push({path, query:{...query,...researchSourceQuery(id.value,workspace.value||"cost",stage.value.step)}});
}
function openMaterials(type:string) {
  void router.push({path:"/product-research",query:{productId:String(id.value),step:launchStage(actualStage.value).key,materials:type}});
}
async function openLinkedCost(row:Form,type:"budget"|"expense") {
  try {
    if(type==='budget' && !row.budgetKind) {
      const result=await getFinanceRows('budget',{projectId:id.value,budgetId:row.budgetId,page:1,size:1});
      if(!result.content.length)throw new Error('关联预算不存在或当前无权查看，请核对原记录');
      row=result.content[0];
    }
    openSource(type==="budget"?"/development-budgets":"/development-expenses",{projectId:id.value,...(type==="budget"?{kind:row.budgetKind,budgetId:row.budgetId}:{expenseId:row.expenseId})});
  } catch(e) {error.value=e instanceof Error?e.message:'关联成本载入失败';ElMessage.error(error.value);}
}
watch(costType,()=>{financePage.value=1;if(workspace.value==='cost' && costType.value!=='unit' && id.value)void loadFinance().catch(e=>{error.value=e instanceof Error?e.message:'成本载入失败'});});
async function loadFinance() {
  if (!canMoney.value) return;
  const project=id.value, requestedPage=financePage.value;
  const query = { projectId: project, page: requestedPage, size: 50 };
  const [b, e, bs, es] = await Promise.all([
    getFinanceRows("budget", query),
    getFinanceRows("expense", query),
    getFinanceSummary("budget", query),
    getFinanceSummary("expense", query),
  ]);
  if(id.value!==project || financePage.value!==requestedPage)return;
  finances.value = { budgets: b.content, expenses: e.content };
  financeSummary.value = { budget: bs, expense: es };
  financeTotals.value = {budget:b.totalElements,expense:e.totalElements};
  quotes.value = detail
    .value!.events.filter((e) => e.kind === "quote")
    .map((e) => ({ ...e.data, eventId: e.eventId }));
}
let supplierChangeBaseline="";
async function closeTask(){
  if(busy.value)return false;
  if(taskOpen.value&&task.value==='supplier_change'&&JSON.stringify(form)!==supplierChangeBaseline){try{await ElMessageBox.confirm('供应商更换尚未保存，放弃本次输入？','未保存供应商更换',{confirmButtonText:'放弃输入',cancelButtonText:'继续填写'});}catch{return false;}}
  taskOpen.value=false;return true;
}
async function beforeTaskClose(done:()=>void){if(await closeTask())done();}
async function canLeaveSupplierEntries(){
  if(supplierFeedbackPanel.value&&!(await supplierFeedbackPanel.value.leave()))return false;
  return !taskOpen.value||task.value!=='supplier_change'||await closeTask();
}
function openTask(value: WorkflowAction, initial: Form = {}) {
  task.value = value;
  clearAssign(form, {
    channel: "ecommerce",
    status: value === "presale" ? "reserved" : "pending",
    type: "budget",
    ...(value === "presale" && !canFinanceWrite.value
      ? {}
      : { currency: "CNY" }),
    category: "other",
    quantity: null,
    phoneModelId: effectivePhones.value[0] || null,
    ownerUserId: profile.ownerUserId,
    releaseId: currentPackage.value?.releaseId,
    technical: false,
    ...initial,
  });
  supplierChangeBaseline=JSON.stringify(form);error.value="";taskOpen.value = true;
}
function openSupplierChange(assignment:SupplierAssignment){
  if(!editable.value||!detail.value?.canChangeSupplier||busy.value||plan.suppliersRestricted)return;
  supplierChangeContext.value={projectId:id.value,stage:stage.value.step,token:user.token};
  openTask('supplier_change',{previousSupplierCode:assignment.supplierCode,previousSupplierName:suppliers.value.find(s=>s.supplierCode===assignment.supplierCode)?.supplierName||assignment.supplierCode,role:assignment.role,scope:assignment.scope,supplierCode:'',replacementPending:false,basis:'',reason:'',stage:stage.value.step});
}
async function submitTask() {
  await action(async () => {
    if(task.value==='supplier_change'){
      const context=supplierChangeContext.value;
      if(!context||context.projectId!==id.value||context.stage!==stage.value.step||context.token!==user.token||!editable.value||!detail.value?.canChangeSupplier)throw Error('项目、阶段或登录权限已变化，请重新打开供应商更换窗口');
      if(!form.supplierCode&&!form.replacementPending)throw Error('请选择新供应商，或明确选择下一家待定');
      if(form.supplierCode===form.previousSupplierCode)throw Error('请选择与原供方不同的新供应商');
      if(!form.reason?.trim())throw Error('请填写供应商更换原因');
      await command('supplier_change',{stage:context.stage,previousSupplierCode:form.previousSupplierCode,role:form.role,scope:form.scope,supplierCode:form.replacementPending?'':form.supplierCode,basis:form.basis,reason:form.reason.trim(),...(form.replacementPending?{replacementPending:true}:{})});
      const updated=form.replacementPending?undefined:((workflow.value.planDraft as Form)?.supplierAssignments||(workflow.value.plan as Form)?.supplierAssignments||[]).find((row:SupplierAssignment)=>row.mode==='supplier'&&row.supplierCode===form.supplierCode&&row.role===form.role&&row.scope===form.scope);
      hydrating=true;
      try{plan.supplierAssignments=(plan.supplierAssignments||[]).map((row:SupplierAssignment)=>row.mode==='supplier'&&row.supplierCode===form.previousSupplierCode&&row.role===form.role&&row.scope===form.scope?(updated?clone(updated):null):row).filter(Boolean);}finally{hydrating=false;}
      supplierChangeContext.value=null;
    }else await command(task.value, {
      ...form,
      ...(["link", "quote"].includes(task.value)
        ? { references: materialReferences(keys.value) }
        : {}),
    });
    taskOpen.value = false;
    if (["cost","promotion","quote"].includes(task.value))
      await loadFinance();
    ElMessage.success(task.value==='supplier_change'?"供应商已更换":"已保存，历史版本保留");
  });
}
let supplierFeedbackAttempt:{signature:string;requestId:WorkflowRequestId}|null=null;
async function submitSupplierFeedback(input:SupplierFeedbackInput,context:string){
 await action(async()=>{
  if(context!==supplierFeedbackContext.value||!editable.value||!detail.value?.canChangeSupplier||plan.suppliersRestricted)throw Error('项目、阶段或登录权限已变化，请重新打开供应商反馈窗口');
  const data={...input,stage:stage.value.step},signature=JSON.stringify([context,data]);
  if(!supplierFeedbackAttempt||supplierFeedbackAttempt.signature!==signature)supplierFeedbackAttempt={signature,requestId:crypto.randomUUID()};
  await command('supplier_feedback',data,supplierFeedbackAttempt.requestId);
  supplierFeedbackAttempt=null;
  if(input.releaseCurrent){
   hydrating=true;
   try{plan.supplierAssignments=(plan.supplierAssignments||[]).filter((a:SupplierAssignment)=>!(a.mode==='supplier'&&a.supplierCode===input.previousSupplierCode&&a.role===input.role&&a.scope===input.assignmentScope));
   }finally{hydrating=false;}
  }
  supplierFeedbackPanel.value?.saved();ElMessage.success(input.voided?'供应商反馈已作废，原记录保留':'供应商反馈已保存');
 });
}
async function loadMoldChoices() {
  await action(async () => {
    const list = await Promise.all(
      effectivePhones.value.map((phone) =>
        getPhoneCompatibleMolds(phone, id.value),
      ),
    );
    moldChoices.value = [
      ...new Map(list.flat().map((m) => [m.moldSetId, m])).values(),
    ];
  });
}
async function linkMold() {
  await action(async () => {
    await command("mold_link", {
      moldSetId: moldChoice.value,
      applicationId: applicationChoice.value,reuseConfirmed:reuseConfirmed.value,reason:reuseReason.value,
    });
    moldChoice.value = null;
    applicationChoice.value = null;
    ElMessage.success("已关联原业务对象");
  });
}
async function unlinkMold(moldId:number,applicationId?:number){await action(async()=>{const link=((workflow.value.moldLinks as Form[])||[]).find(l=>moldId?l.moldSetId===moldId:l.applicationId===applicationId);if(!link)throw Error('未找到本项目的关联');const response=await ElMessageBox.prompt('填写取消关联原因，原申请、模具及实际记录继续保留。','取消本项目关联',{inputValidator:v=>!!v?.trim()||'请填写原因'});await command('mold_unlink',{moldSetId:link.moldSetId||0,applicationId:link.applicationId||0,reason:response.value});});}
function applicationStatus(application:Form){return moldBusinessStatusNames[(application.businessStatus||application.status) as keyof typeof moldBusinessStatusNames]||'查看审批状态';}
function applicationPhoneLabel(application:Form){const phone=phones.value.find(row=>row.phoneModelId===application.phoneModelId);return phone?phoneMemberName(phone):application.standardPhone?.modelName||application.phoneModelName||application.model||(application.phoneModelId?'标准手机资料待读取':'标准手机待补充');}
function openMoldApplicationEntry(){const application=primaryMoldApplication.value;if(!application)return prepareMoldApplication();return canContinueMoldDraft.value?editMoldApplication(application.applicationId):showApplicationProgress(application.applicationId);}
function showApplicationProgress(applicationId:number){const destination={path:'/mold-applications',query:{applicationId:String(applicationId)}};if(busy.value){queuedRoute=destination;return;}return router.push(destination);}
async function prepareMoldApplication(){
  if(!canPrepareMoldApplication.value||busy.value)return;
  const context={epoch:++moldApplicationEpoch,project:id.value,auth:user.token};
  await action(async()=>{
    const source=await getResearchMoldApplicationContext({projectId:context.project,version:version.value});
    if(!currentMoldApplication(context))return;
    if(source.productLineId!==context.project||source.sourceSnapshot?.research?.projectId!==context.project)throw Error('返回的项目申请资料不匹配，请刷新后重试');
    if(source.existingApplication){await loadMoldApplicationEditor(source.existingApplication.applicationId,context);return;}
    await moldApplicationEditor.value?.openResearch(source,{projectInputUnsaved:dirty.value});
  });
}
type MoldApplicationContext={epoch:number;project:number;auth:string};
function currentMoldApplication(context:MoldApplicationContext){return context.epoch===moldApplicationEpoch&&context.project===id.value&&context.auth===user.token;}
async function loadMoldApplicationEditor(applicationId:number,context:MoldApplicationContext){
  const row=await getMoldApplication(applicationId).catch(error=>{if(!currentMoldApplication(context))return undefined;throw error;});
  if(!row||!currentMoldApplication(context))return false;
  if(row.status!=='draft'||row.canEditDraft!==true){await showApplicationProgress(row.applicationId);return false;}
  await moldApplicationEditor.value?.open(row);
  return currentMoldApplication(context);
}
async function editMoldApplication(applicationId:number){
  if(busy.value)return;
  const context={epoch:++moldApplicationEpoch,project:id.value,auth:user.token};
  await action(async()=>{await loadMoldApplicationEditor(applicationId,context);});
}
function moldApplicationSaved(){void refresh().catch(()=>{});}
function moldApplicationSubmitted(){void refresh().catch(()=>{});ElMessage.success('已正式提交钉钉，可在项目的开模申请中查看进度');}
watch(()=>[id.value,user.token],()=>{++moldApplicationEpoch;});
async function confirmNoMold(){await action(async()=>{await command('no_mold',{reason:noMoldReason.value,references:materialReferences(keys.value)});dirty.value=false;ElMessage.success('已核对当前方案的无需开模依据');});}
async function restartMold(mold: Form) {
  await action(async () => {
    const response = await ElMessageBox.prompt(
      "填写本轮重新验模原因，旧通过记录保留。",
      "重新验模",
      {
        confirmButtonText: "确认",
        cancelButtonText: "取消",
        inputValidator: (v) => !!v?.trim() || "请填写原因",
      },
    );
    await command("mold_restart", {
      moldSetId: mold.moldSetId,
      nodeVersion: mold.development?.version || 0,
      reason: response.value,
      references: materialReferences(keys.value),
    });
    openNode(molds.value.find((m) => m.moldSetId === mold.moldSetId)!);
  });
}
const imageUploadAccept='.jpg,.jpeg,.png,.webp';
const uploadExtension=(file:File)=>file.name.slice(file.name.lastIndexOf('.')).toLowerCase();
function validateUploadFiles(files:(File|undefined)[],imagesOnly:boolean){
 if(!files.length)throw Error('请选择本地文件');
 if(files.length>20)throw Error('每次最多选择20份文件，请分批上传');
 const allowed=new Set((imagesOnly?imageUploadAccept:ENGINEERING_UPLOAD_ACCEPT).split(','));
 for(const file of files){
  if(!file)throw Error('请选择本地文件');
  if(file.size>MAX_UPLOAD_FILE_SIZE_BYTES)throw Error(`${file.name} 超过200 MB，请移除后重试`);
  if(!allowed.has(uploadExtension(file)))throw Error(`${file.name} 不是支持的图片、文档、压缩包或工程文件，请重新选择`);
 }
}
function prepareLocalUpload(files:File[]){
 if(!editable.value||busy.value||uploadOpen.value||!files.length)return;
 const imagesOnly=files.every(file=>imageUploadAccept.split(',').includes(uploadExtension(file)));
 try{validateUploadFiles(files,imagesOnly);}catch(error){ElMessage.warning(requestError(error));return;}
 prepareUpload(null,imagesOnly);
 pendingFiles.value=files.map(file=>{const raw=file as UploadRawFile;raw.uid=genFileId();return {uid:raw.uid,name:file.name,size:file.size,status:'ready' as const,raw};});
}
function prepareUpload(previous:DevelopmentMaterial|null=null,imagesOnly=false){
 if(!editable.value||busy.value)return;
 uploadPrevious.value=previous;pendingFiles.value=[];uploadError.value='';uploadedFileCount.value=0;
 uploadImagesOnly.value=imagesOnly;
 Object.assign(fileForm,{category:previous?.category||(imagesOnly?(stage.value.step===1?'drawing':'sample_image'):'other'),versionNo:previous?'':'V1',reason:''});uploadOpen.value=true;
}
async function upload(){if(!editable.value||busy.value)return;await action(async()=>{
 uploadError.value='';
 try{
  if(!pendingFiles.value.length||!fileForm.versionNo.trim())throw Error('请选择文件并填写版本号');
  if(uploadPrevious.value&&!fileForm.reason.trim())throw Error('请填写新版本变更原因');
  validateUploadFiles(pendingFiles.value.map(entry=>entry.raw),uploadImagesOnly.value);
  const uploaded:AttachmentVO[]=[];let failure:unknown;
  try{
   for(const entry of [...pendingFiles.value]){
    if(!entry.raw)throw Error('请选择本地文件');
    const attachment=uploadPrevious.value?await uploadAttachmentVersion(uploadPrevious.value.id,entry.raw,fileForm.versionNo,fileForm.reason):await uploadProjectAttachment(id.value,entry.raw,{fileCategory:fileForm.category,versionNo:fileForm.versionNo,remark:fileForm.reason});
    uploaded.push(attachment);uploadedFileCount.value++;pendingFiles.value=pendingFiles.value.filter(f=>f.uid!==entry.uid);
   }
  }catch(e){failure=e;}
  if(uploaded.length){
   if(!uploadPrevious.value&&materialsEditable.value&&tab.value==='work')keys.value=[...new Set([...keys.value,...uploaded.map(a=>`file:${a.attachmentId}`)])];
   await reloadMaterials();
  }
  if(failure)throw failure;
  uploadOpen.value=false;ElMessage.success(`已上传 ${uploadedFileCount.value} 份资料，可在资料区预览并选择采用版本`);
 }catch(e){uploadError.value=requestError(e);throw e;}
});}
async function reloadMaterials(){const project=id.value,token=user.token;const value=await getProductResearchDetail(project);if(project===id.value&&token===user.token)childUpdated(value);}
async function openEngineering(kind:string){if(busy.value)return;engineering.value=kind;engineeringOpen.value=true;}
async function downloadMaterial(m:DevelopmentMaterial){await action(async()=>viewer.downloadFile(m.attachment||await getAttachmentDetail(m.id)));}
async function removeMaterial(m:DevelopmentMaterial){await action(async()=>{await ElMessageBox.confirm(`删除“${m.name} · ${m.versionNo}”草稿？已被开发记录或正式发布引用的版本会保留。`,'删除资料草稿',{confirmButtonText:'删除',cancelButtonText:'取消'});await deleteAttachment(m.id);keys.value=keys.value.filter(k=>k!==materialKey(m));await reloadMaterials();ElMessage.success('资料草稿已删除');});}
async function viewMaterial(m: DevelopmentMaterial) {
  if (m.type === "file") await action(async()=>{previewFile.value=m.attachment||await getAttachmentDetail(m.id);});
  else if (m.type === "link") {
    const url = String(m.content?.url || "");
    if (/^https?:\/\//.test(url))
      window.open(url, "_blank", "noopener,noreferrer");
  } else inspect(m, `${m.name} · ${m.versionNo}`);
}
async function publish() {
  await action(async () => {
    if (
      missing.value.length &&
      (!publication.risk || !publication.reason.trim() || !publication.dueDate)
    )
      throw Error("请确认缺口，填写例外原因与补齐日期");
    await publishProject(id.value, {
      reason: publication.reason || "新品正式定型",
      riskConfirmed: publication.risk,
      exceptionReason: publication.reason,
      supplementDueDate: publication.dueDate || null,
      developmentResponsibleUserId:
        release.responsibleUserId || profile.ownerUserId,
      developmentReferences: materialReferences(keys.value),
      developmentVersion: version.value,
    });
    publicationOpen.value = false;
    dirty.value = false;
    await refresh();
    ElMessage.success("正式包已保存；实际上市另行登记");
  });
}
function moldProgress(m: Form) {
  const d = m.development || {};
  if (d.last?.result === "blocked" && d.node === d.last?.node)
    return "制作／改模遇阻";
  return (
    (
      {
        making: "待确认制作完成",
        testing: "待验模",
        rework: "待改模",
        ready: "验模通过",
      } as Record<string, string>
    )[d.node] || "制作进度待核对"
  );
}
function moldActualDate(m: Form) {
  const d = m.development || {};
  return d.node === "ready" || d.last?.result === "blocked"
    ? d.last?.actualDate || "未确认"
    : "未确认";
}
function resultLabel(value: string) {
  return (
    (
      { passed: "通过", failed: "需改进", pending: "待验证" } as Record<
        string,
        string
      >
    )[value] || value
  );
}
</script>

<template>
  <section ref="pageElement" class="launch-page" v-loading="loading">
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    ><el-button v-if="id&&supplierFeedbackLoadContext!==feedbackProjectContext" link :disabled="loading" @click="load">重新载入</el-button></el-alert>
    <template v-if="!id">
      <header class="toolbar">
        <h2>新品项目</h2>
        <el-input
          v-model="keyword"
          clearable
          placeholder="搜索产品、机型或开发要求"
          @keyup.enter="
            page = 1;
            load();
          "
        /><el-button
          @click="
            page = 1;
            load();
          "
          >搜索</el-button
        ><el-button
          v-if="user.hasPermission('documents:write')"
          type="primary"
          @click="newOpen = true"
          >新建新品</el-button
        >
      </header>
      <div class="filters">
        <el-radio-group v-model="state"
          ><el-radio-button value="active">待办理</el-radio-button
          ><el-radio-button value="completed">已完成</el-radio-button
          ><el-radio-button value="all"
            >历史与全部</el-radio-button
          ></el-radio-group
        >
      </div>
      <div class="list-scroll">
        <el-empty
          v-if="!rows.length"
          :description="`暂无新品项目`"
        />
        <article v-for="row in rows" :key="row.productId" class="project-row">
          <div>
            <strong>{{ row.productName }}</strong
            ><small
              >{{ row.productCode }} · {{ row.model || "机型待定" }}</small
            >
          </div>
          <div>
            {{ row.currentStage }}{{row.workflowComplete?' · 已完成':''}}<small
              >{{ row.ownerName || "负责人待分配" }} ·
              {{ row.expectedDeliveryDate || "计划时间待定" }}</small
            ><div class="supplier-line"><span v-if="row.supplierNames?.length">{{row.supplierNames.join('、')}}</span><el-button class="supplier-feedback-summary" link :type="row.supplierFeedbackSummary?.nextSupplierPending&&!row.supplierFeedbackSummary?.restricted?'warning':undefined" :disabled="row.supplierFeedbackSummary?.restricted" :aria-label="`查看${row.productName}的供应商反馈原因与附件`" @click="routeTo(row.currentStepNo||1,row.productId,'supplier-feedback')">{{supplierFeedbackLabel(row.supplierFeedbackSummary)}}</el-button><span v-if="row.nextAction">{{row.nextAction}}</span></div>
          </div>
          <div class="row-actions"><el-button
            type="primary"
            link
            @click="routeTo(row.currentStepNo||1, row.productId)"
            >查看／办理</el-button
          ><el-button v-if="row.currentStepNo===1 && row.status==='draft' && user.hasPermission('documents:write')" link type="danger" @click="deleteProjectDraft(row)">删除草稿</el-button></div>
        </article>
      </div>
      <el-pagination
        v-model:current-page="page"
        :page-size="50"
        :total="total"
        layout="prev,pager,next,total"
      />
    </template>
    <template v-else-if="detail&&detail.productId===id">
      <header class="toolbar project-header">
        <el-button text @click="workspace ? router.push(researchBusinessRoute(workspace)) : routeTo(stage.step, 0)">{{ workspace ? "全部项目" : "返回列表" }}</el-button>
        <div class="heading">
          <h2>{{ detail.productName }}</h2>
          <small
            >{{ detail.productCode }} · {{ detail.ownerName || "负责人待分配" }} · 当前：{{
              PRODUCT_RESEARCH_STAGES[actualStage - 1]?.name
            }}<span v-if="workflow.revision">
              · R{{ workflow.revision.revisionNo }} 修订中</span
            ></small
          >
        </div>
        <div class="workspace-links">
          <el-button v-if="showMoldApplicationEntry&&(primaryMoldApplication||moldApplicationEntry?.canCreate)" type="primary" :disabled="busy||(!primaryMoldApplication&&!canPrepareMoldApplication)" @click="openMoldApplicationEntry">{{moldApplicationEntryLabel}}</el-button>
          <el-button v-if="canMoney" :type="workspace==='cost'?'primary':undefined" @click="openWorkspace('cost')">成本</el-button>
          <el-dropdown trigger="click" placement="bottom-end" @command="projectCommand">
            <el-button aria-label="项目操作">项目操作<el-icon class="project-menu-arrow"><ArrowDown/></el-icon></el-button>
            <template #dropdown><el-dropdown-menu class="research-project-menu">
              <el-dropdown-item command="logs">系统日志</el-dropdown-item>
              <el-dropdown-item v-if="!workspace" command="materials">定位资料（{{currentMaterials.filter(m=>m.type==='file').length}}）</el-dropdown-item>
              <el-dropdown-item command="promotion">推广</el-dropdown-item>
              <el-dropdown-item command="presale">预售</el-dropdown-item>
              <el-dropdown-item v-if="!workspace && editable && actualStage===1 && !workflow.requirement" divided command="delete" class="delete-project-command">删除新品草稿</el-dropdown-item>
            </el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>
      <section v-if="showMoldApplicationEntry" class="research-mold-entry" aria-label="LEMOS开模申请">
        <p class="muted">{{primaryMoldApplication?'本新品项目已有关联的LEMOS申请，请继续原草稿或查看原审批进度。':canPrepareMoldApplication?'已保存的项目资料会带入申请表，缺项可在表内补齐或先保存草稿。':moldApplicationEntry?.reason||'当前无权准备开模申请，请联系项目工程负责人核对。'}}</p>
        <div v-for="application in applications" :key="application.applicationId" class="research-application-row">
          <span><strong>{{application.approvalNo||`申请 #${application.applicationId}`}}</strong> · {{applicationPhoneLabel(application)}} · {{applicationStatus(application)}}</span>
          <el-button v-if="application.status==='draft'&&application.canEditDraft===true&&moldApplicationEntry?.canCreate" link type="primary" :disabled="busy" @click="editMoldApplication(application.applicationId)">继续填写</el-button>
          <el-button v-else link type="primary" :disabled="busy" @click="showApplicationProgress(application.applicationId)">查看审批进度</el-button>
        </div>
      </section>
      <div v-if="!workspace && stage.step !== actualStage" class="readonly-notice">
        当前项目处于{{
          PRODUCT_RESEARCH_STAGES[actualStage - 1]?.name
        }}，本页仅查看。<el-button
          link
          type="primary"
          @click="routeTo(actualStage)"
          >前往当前阶段</el-button
        >
      </div>
      <MoldApplicationEditor ref="moldApplicationEditor" @saved="moldApplicationSaved" @submitted="moldApplicationSubmitted" @progress="showApplicationProgress"/>
      <el-collapse v-if="workspace" class="business-relations"><el-collapse-item title="关联业务 · 查看项目、模具、资料和费用来源" name="relations">
        <div class="relation-links">
          <el-button link type="primary" @click="routeTo(actualStage)">{{detail.productCode}} · {{detail.productName}} · 当前{{PRODUCT_RESEARCH_STAGES[actualStage-1]?.name}}</el-button>
          <el-button link @click="routeTo(3)">样品 {{sampleRows.length}} 轮</el-button><el-button v-if="user.hasPermission('partner:view')" link @click="router.push({path:'/samples',query:{projectId:id,from:route.fullPath}})">实物样品台账</el-button><el-button v-if="user.hasPermission('samples:write')" link @click="router.push({path:'/research-samples',query:{projectId:id,new:'1',from:route.fullPath}})">登记实际收样</el-button>
          <el-button v-if="user.hasPermission('file:read')" link @click="openSource('/files',{projectId:id})">图纸与文件 {{currentMaterials.filter(m=>m.type==='file').length}} 版</el-button>
          <el-button v-if="user.hasPermission('product:view')" link @click="openMaterials('bom')">BOM {{currentMaterials.filter(m=>m.type==='bom').length}} 版</el-button>
          <el-button v-if="user.hasPermission('process:view')" link @click="openMaterials('process')">工艺 {{currentMaterials.filter(m=>m.type==='process').length}} 版</el-button>
          <el-button v-if="user.hasPermission('collaboration:view')" link @click="openSource('/mold-applications',{projectId:id})">开模申请 {{applications.length}} 条</el-button>
          <el-button v-for="m in molds" :key="m.moldSetId" link @click="openSource('/molds',{moldSetId:m.moldSetId})">模具：{{m.assetNumber||m.setName||m.moldSetId}}</el-button>
          <el-button v-if="user.hasPermission('schedule:read') && molds.length" link @click="openSource('/mold-schedules',{projectId:id})">原开模排程</el-button>
        </div><p class="muted">预算和费用沿用费用管理原台账；推广、预售按本项目保存。预售尚未关联销售订单，数量不写入生产或收款。</p>
      </el-collapse-item></el-collapse>
      <template v-if="workspace">
        <header class="toolbar">
          <el-button @click="routeTo(actualStage)">返回当前研发阶段</el-button
          ><el-button v-if="workspace !== 'cost'" @click="inspect(detail.events.filter(e=>e.kind===workspace).map(e=>({...e.data,recordDate:e.createdAt,ownerName:e.createdBy})),workspace==='promotion'?'推广变更历史':'预售变更历史')">变更历史</el-button><span v-if="confirmedPlan.launchDate" class="muted"
            >计划上市 {{ confirmedPlan.launchDate }}</span
          >
        </header>
        <div class="list-scroll">
          <template v-if="workspace === 'cost'">
            <div class="toolbar">
              <el-radio-group :model-value="costType" @change="changeCostType"
                ><el-radio-button value="unit">单件成本</el-radio-button><el-radio-button value="budget">预算</el-radio-button
                ><el-radio-button value="quote">报价</el-radio-button
                ><el-radio-button value="expense"
                  >费用</el-radio-button
                ></el-radio-group
              ><el-button
                v-if="
                  canFinanceWrite && parallelWritable && !['quote','unit'].includes(costType)
                "
                type="primary"
                @click="
                  openTask('cost', {
                    type: costType,
                    name: costType === 'budget' ? '项目开发预算' : '',
                  })
                "
                >登记{{
                  costType === "budget" ? "项目预算" : "费用"
                }}</el-button
              ><el-button
                v-if="
                  costType === 'quote' &&
                  parallelWritable &&
                  canFinanceWrite
                "
                @click="openTask('quote', { unit: '每件' })"
                >新增供方报价</el-button
              >
            </div>
            <UnitCostSheet ref="costSheet" v-if="costType==='unit'" :detail="detail" @updated="childUpdated"/>
            <template v-else>
            <p class="muted">
              目标单件成本：{{ profile.targetCost ?? "待定" }}
              {{ profile.currency }}／{{ profile.costUnit }}。预算、报价、申报和
              ERP 入账分开核对。
            </p>
            <div
              class="summary-line"
              v-for="group in financeSummary[costType]?.currencyGroups || []"
              :key="String(group.currencyCode)"
            >
              <strong
                >{{ group.currencyCode }} ·
                {{
                  costType === "budget"
                    ? group.budgetKind === "platform"
                      ? "项目总预算"
                      : "模具分项预算"
                    : "费用"
                }}</strong
              ><span v-if="costType === 'budget'">
                {{ group.budgetTotal ?? "待定" }} · 未知
                {{ group.unknownCount }} 项</span
              ><span v-else>
                申报 {{ group.declaredTotal ?? "待定" }} · 审批
                {{ group.approvedTotal ?? "待定" }} · ERP净额
                {{ group.erpPostedNet ?? "待定" }}</span
              >
            </div>
            <template v-if="costType !== 'quote'"
              ><article
                v-for="row in costType === 'budget'
                  ? finances.budgets
                  : finances.expenses"
                :key="String(row.budgetId || row.expenseId)"
                class="business-row"
              >
                <strong>{{ row.budgetName || row.description }}</strong>
                <div>
                  {{ row.currencyCode }} ·
                  {{
                    costType === "budget"
                      ? `预算 ${row.calculatedTotal ?? row.budgetAmount ?? "未知"}`
                      : `申报 ${row.declaredAmount ?? "未知"} · ${row.sourceSystem === "erp" ? "ERP 入账" : "手工记录"}`
                  }}
                </div>
                <div class="row-actions">
                  <el-button link @click="openLinkedCost(row,costType==='budget'?'budget':'expense')">查看原台账与历史</el-button><el-button link @click="inspect(row, '原台账当前值')"
                    >详情</el-button
                  ><el-button
                    v-if="
                      canFinanceWrite &&
                      parallelWritable && ownedCost(row) &&
                      (costType === 'budget' ? row.budgetKind === 'platform' : row.state === 'draft')
                    "
                    link
                    type="primary"
                    @click="
                      openTask('cost', {
                        type: costType,
                        id: row.budgetId || row.expenseId,
                        name: row.budgetName || row.description,
                        amount: row.budgetAmount ?? row.declaredAmount,
                        currency: row.currencyCode,
                        category: row.expenseCategory || 'other',
                        expectedUpdatedAt: row.updatedAt,
                        expectedVersionNo: row.versionNo,
                      })
                    "
                    >修改留痕</el-button
                  >
                </div>
              </article>
              <el-pagination
                v-if="financeTotal > 50"
                v-model:current-page="financePage"
                :total="financeTotal"
                :page-size="50"
                layout="prev,pager,next,total"
                @current-change="loadFinance"
            /></template>
            <template v-else
              ><article
                v-for="q in quotes"
                :key="q.eventId"
                class="business-row"
              >
                <strong
                  >{{ q.supplierName }} · {{ q.amount }} {{ q.currency }}／{{
                    q.unit
                  }}</strong
                ><span>{{ q.conditions || "条件见版本详情" }}</span
                ><el-button link @click="inspect(q, '样品报价版本')"
                  >查看版本</el-button
                >
              </article>
              <article
                v-for="m in molds"
                :key="m.moldSetId"
                class="business-row"
              >
                <strong>{{ m.assetNumber || m.setName }} · 原模具报价</strong
                ><span
                  >{{ m.quoteAmount ?? "未知" }} {{ m.quoteCurrency }} ·
                  {{ m.quoteConfirmed ? "已确认" : "待核对" }}</span
                ><el-button
                  link
                  @click="
                    openSource('/molds', { moldSetId: m.moldSetId })
                  "
                  >维护原报价</el-button
                >
              </article></template
            >
            <div class="row-actions">
              <el-button
                @click="
                  openSource('/development-budgets', { projectId: id, kind: 'platform' })
                "
                >原预算台账</el-button
              ><el-button
                @click="
                  openSource('/development-expenses', { projectId: id })
                "
                >原费用归集</el-button
              >
            </div>
            </template>
          </template>
          <template v-else-if="workspace === 'promotion'"
            ><div class="toolbar">
              <h3>渠道推广</h3>
              <el-button
                v-if="parallelWritable"
                type="primary"
                @click="openTask('promotion')"
                >新增推广</el-button
              >
            </div>
            <article
              v-for="r in promotionRows"
              :key="r.id"
              class="business-row"
            >
              <strong>{{ channelNames[r.channel] }} · {{ r.target }}</strong
              ><span
                >{{ r.action }} · {{ r.plannedDate }} ·
                {{ statusNames[r.status] }}</span
              >
              <div class="row-actions">
                <el-tag
                  v-if="r.planVersion !== confirmedPlan.planVersion"
                  type="warning"
                  >计划已调整，需核对</el-tag
                ><el-button link @click="inspect(r, '推广当前版本')"
                  >详情</el-button
                ><el-button
                  v-if="parallelWritable"
                  link
                  type="primary"
                  @click="openTask('promotion', r)"
                  >更新进度</el-button
                ><el-button v-if="canMoney && r.budgetId" link @click="openLinkedCost({budgetId:r.budgetId},'budget')">关联预算 #{{r.budgetId}}</el-button>
                <el-button v-if="canMoney && r.expenseId" link @click="openLinkedCost({expenseId:r.expenseId},'expense')">关联费用 #{{r.expenseId}}</el-button>
              </div>
            </article>
            <el-empty
              v-if="!promotionRows.length"
              description="按需安排素材、电商、MCN 及线下合作。"
          /></template>
          <template v-else
            ><div class="toolbar">
              <h3>预售需求 · 有效 {{ presaleQty }} 件</h3>
              <el-button
                v-if="parallelWritable"
                type="primary"
                @click="openTask('presale', { channel: '电商' })"
                >登记预售</el-button
              >
            </div>
            <article v-for="r in presaleRows" :key="r.id" class="business-row">
              <strong>{{ r.target }} · {{ r.channel }}</strong
              ><span
                >{{
                  r.phoneSnapshot?.modelName || phoneLabel(r.phoneModelId)
                }}
                · {{ r.quantity }} 件 · 承诺 {{ r.promisedDate }}</span
              >
              <div class="row-actions">
                <el-tag>{{ statusNames[r.status] }}</el-tag
                ><el-tag
                  v-if="r.planVersion !== confirmedPlan.planVersion"
                  type="warning"
                  >计划已调整，承诺未改</el-tag
                ><el-button link @click="inspect(r, '预售当前版本')"
                  >详情</el-button
                ><el-button
                  v-if="parallelWritable"
                  link
                  type="primary"
                  @click="openTask('presale', r)"
                  >调整／取消</el-button
                >
              </div>
            </article>
            <el-empty
              v-if="!presaleRows.length"
              description="预售记录关联本项目，不自动生成生产或收款。"
          /></template>
        </div>
      </template>
      <template v-else>
        <nav class="research-step-nav" aria-label="研发进度">
          <button v-for="item in PRODUCT_RESEARCH_STAGES" :key="item.key" type="button" :class="{current:item.step===stage.step,done:stepComplete(item.step)}" :aria-current="item.step===stage.step?'step':undefined" :aria-label="`${item.name}，${item.step===stage.step?'正在查看':stepComplete(item.step)?'已完成':'未完成，可查看'}`" @click="routeTo(item.step)"><span class="step-number"><span v-if="stepComplete(item.step)&&item.step!==stage.step" class="step-complete-mark" aria-hidden="true">✓</span><span v-else>{{item.step}}</span></span><span class="step-name">{{item.name}}</span></button>
        </nav>
        <div v-if="stage.step>1" class="stage-context">
          <span class="muted">{{ effectivePhones.map(phoneLabel).join('、') }}<template v-if="confirmedPlan.launchDate"> · 计划上市 {{ confirmedPlan.launchDate }}</template></span>
          <div class="row-actions"><template v-if="editable&&actualStage===4"><el-button link data-readiness-target="previous-stage" @click="returnPrevious(2)">返回画图</el-button><el-button link @click="returnPrevious(3)">重新3D打样</el-button></template><el-button v-else-if="editable && actualStage > 1" link data-readiness-target="previous-stage" @click="returnPrevious()">退回上一阶段</el-button></div>
        </div>
        <div v-if="moldSupplier&&![1,5,7].includes(stage.step)" class="toolbar"><span>开模供应商：{{suppliers.find(s=>s.supplierCode===moldSupplier?.supplierCode)?.supplierName||moldSupplier.supplierCode}}</span><el-button v-if="editable&&detail.canChangeSupplier" link @click="openSupplierChange(moldSupplier)">更换供应商</el-button></div>
        <StageReadinessChecklist v-if="stage.step===actualStage" ref="readinessPanel" :product-id="id" :version="version" :stage="stage.step" :draft="stageDraft" :writable="editable" :busy="busy" @checked="readinessChecks=$event?.checks||[]" @locate="locateStageGap"/>
        <div class="development-workspace"><el-tabs v-model="tab" class="stage-tabs" :class="{'single-work-tab':![6,7].includes(stage.step)}" :before-leave="beforeStageTabLeave">
          <el-tab-pane :label="stage.step===6?'制作与试模':stage.step===7?'试产资料':''" name="work">
            <div :key="`${id}:${stage.step}:${workKind}`" ref="designForm" class="form-area"><div v-if="stage.step===6" class="toolbar"><el-radio-group :model-value="validationPane" @change="switchValidationPane"><el-radio-button value="mold">制作／试模／改模</el-radio-button><el-radio-button value="sample">试模样品</el-radio-button></el-radio-group></div>
              <template v-if="stage.step===1">
                <section class="work-section basic-section"><div class="section-heading"><h3>基本资料与开发需求</h3><span class="muted">委外开模</span></div>
                  <el-form class="step-fields" label-position="top" :disabled="!editable||busy">
                    <el-form-item label="产品名称" required data-readiness-target="product-name" :error="fieldError('product-name')"><el-input v-model="profile.productName" maxlength="255" placeholder="例如：透甲2.0手机壳"/></el-form-item>
                    <el-form-item label="项目负责人" required data-readiness-target="owner" :error="fieldError('owner')"><el-select v-model="profile.ownerUserId" filterable clearable placeholder="请选择负责推进这款新品的人"><el-option v-for="o in owners" :key="o.userId" :label="o.name" :value="o.userId"/></el-select></el-form-item>
                    <el-form-item label="开发需求" required data-readiness-target="requirements" :error="fieldError('requirements')"><el-input ref="requirementsInput" v-model="profile.requirements" type="textarea" :autosize="{minRows:3}" placeholder="写清想做的结构、功能和外观，例如：透明PC背板＋TPU包边，带磁吸和翻转支架。"/></el-form-item>
                    <el-form-item label="意向开模供应商" data-readiness-target="supplier"><el-select :model-value="moldSupplier?.supplierCode" filterable :disabled="!canSelectSupplier" :placeholder="nextMoldSupplierPending?'下一家待定':'待确定'" @change="chooseMoldSupplier"><el-option v-for="o in suppliers" :key="o.supplierCode" :value="o.supplierCode" :label="o.supplierName"/></el-select><el-button v-if="editable&&moldSupplier&&detail.canChangeSupplier" link @click="openSupplierChange(moldSupplier)">更换供应商</el-button></el-form-item>
                  </el-form>
                </section>
              </template>
              <template v-else-if="stage.step===2">
                <section class="work-section"><h3>代表型号与设计说明</h3>
                <el-form class="step-fields" label-position="top" :disabled="!editable||busy">
                  <el-form-item label="本次代表型号" required class="full-field" data-readiness-target="phones"><SharedCompatiblePhoneSelector v-model="plan.phoneModelIds" :members="phones" aria-label="本次代表型号" @selected="rememberPhones"/></el-form-item>
                  <el-form-item label="设计说明" class="full-field"><el-input v-model="stepForm.notes" type="textarea" autosize placeholder="说明本次产品图的结构、功能及版本改动"/></el-form-item>
                  <el-form-item v-if="workflow.stage2Basis" label="方案调整原因" class="full-field"><el-input v-model="plan.reason" type="textarea" autosize placeholder="说明为何调整已采用的设计方案"/></el-form-item>
                </el-form></section>
              </template>
              <template v-else-if="stage.step===4"><DevelopmentReviewPanel ref="reviewPanel" :detail="detail" :stage="4" :owners="owners" @updated="childUpdated" @view="viewMaterial"/></template>
              <template v-else-if="stage.step===8&&!confirmedPlan.needsMold"><p>本项目已确认无需开模，发模不适用。</p><el-button link @click="inspect(workflow.noMold,'无需开模依据')">查看确认依据</el-button></template>
              <template v-else-if="stage.step===8">
                <h3 class="work-heading">本批发模与物流</h3>
                <div class="toolbar"><el-button v-if="user.hasPermission('schedule:read')" @click="openSource('/mold-schedules',{projectId:id})">登记实际发模／物流</el-button><el-button v-if="editable" type="primary" :disabled="busy" @click="confirmShipmentBatch">确认本批发模</el-button><el-button v-if="workflow.finalized&&detail.canPublish&&!published" @click="publicationOpen=true">形成正式包</el-button><el-button v-if="canMaintainPublished" @click="openTask('start_revision')">发起资料修订</el-button></div>
                <article v-for="m in molds" :key="m.moldSetId" class="mold-row" :data-readiness-target="`shipment:${m.moldSetId}`"><strong>{{m.assetNumber||m.setName}}</strong><span>{{m.compatibleModels}}</span><span>{{m.actualShipDate||'尚未发模'}}</span><el-button link @click="openSource('/mold-schedules',{projectId:id,moldSetId:m.moldSetId})">查看／维护</el-button></article>
                <el-form class="step-fields" label-position="top" :disabled="!editable||busy"><el-form-item label="本批模具套" required><el-select v-model="stepForm.moldSetIds" multiple><el-option v-for="m in molds" :key="m.moldSetId" :value="m.moldSetId" :label="m.assetNumber||m.setName||String(m.moldSetId)"/></el-select></el-form-item><el-form-item label="墨西哥收货地点／接收方" required data-readiness-target="shipment"><el-input v-model="stepForm.recipient"/></el-form-item><el-form-item label="物流说明"><el-input v-model="stepForm.notes" type="textarea" autosize/></el-form-item><el-form-item label="更正已确认批次时填写原因"><el-input v-model="stepForm.reason" type="textarea" autosize/></el-form-item></el-form><article v-for="batch in (workflow.shipmentBatches as Form[]||[])" :key="batch.id" class="business-row"><strong>发模批次 {{batch.id}}</strong><span>{{batch.recipient}} · {{batch.confirmedBy}} · {{batch.confirmedAt}}</span><el-button link @click="inspect(batch,'已确认发模批次')">查看本批依据</el-button></article>
              </template>
              <template v-else-if="workKind === 'sample'"
                ><h3 class="work-heading">{{stage.step===3?'3D 打样与实际收样':'试模样品与收样'}}</h3><div class="toolbar">
                  <el-select
                    :model-value="activeSampleId"
                    placeholder="选择样品轮次"
                    @change="
                      openSample(sampleRows.find((r) => r.roundId === $event)!)
                    "
                    ><el-option
                      v-for="r in sampleRows"
                      :key="r.roundId"
                      :value="r.roundId"
                      :label="`S${r.roundNo} · ${resultLabel(r.result)} · ${r.status === 'confirmed' ? (r.details?.samplePurpose==='prototype_3d'?'已收样，待负责人确认':'已确认') : '草稿'}`" /></el-select
                  ><el-button v-if="editable" @click="newSample(selectedSample)"
                    >新增一轮</el-button
                  ><el-button v-if="editable && selectedSample?.status==='confirmed'&&!selectedSample.voided" link type="danger" @click="voidSample">作废本轮样品</el-button><el-button v-if="editable && selectedSample?.status==='draft'" link type="danger" @click="deleteSampleDraft">删除样品草稿</el-button>
                </div>
                <el-form class="sample-form" label-position="top" :disabled="sampleReadOnly || busy"
                  ><el-form-item label="样品来源／制作方"
                    ><el-select
                      v-model="sample.details.supplierCode"
                      filterable
                      clearable
                      placeholder="选择实际制作供应商"
                      @change="
                        sample.details.makerName =
                          suppliers.find((s) => s.supplierCode === $event)
                            ?.supplierName || ''
                      "
                      ><el-option
                        v-for="s in suppliers"
                        :key="s.supplierCode"
                        :value="s.supplierCode"
                        :label="s.supplierName" /></el-select
                    ><el-input
                      v-if="!sample.details.supplierCode"
                      v-model="sample.details.makerName"
                      placeholder="实际制作方／样品来源" /></el-form-item
                  ><el-form-item label="实际收样日期"
                    ><el-date-picker
                      v-model="sample.recordDate"
                      value-format="YYYY-MM-DD" /></el-form-item
                  ><el-form-item v-if="stage.step!==3" label="收样与初检结论"
                    ><el-radio-group v-model="sample.result"
                      ><el-radio value="pending">待验证</el-radio
                      ><el-radio value="passed">通过</el-radio
                      ><el-radio value="failed"
                        >需改进</el-radio
                      ></el-radio-group
                    ></el-form-item
                  ><el-form-item
                    v-if="sample.result === 'passed'"
                    label="初检说明（详细结果在评审中记录）"
                    ><el-input
                      v-model="sample.details.actualResult"
                      type="textarea"
                      :autosize="{minRows:2}"
                      placeholder="本次试装、按键等实际结果" /></el-form-item
                  ><el-form-item
                    v-if="sample.result === 'failed'"
                    label="本轮问题"
                    ><el-input
                      v-model="sample.issues"
                      type="textarea"
                      :autosize="{minRows:2}" /></el-form-item
                  ><section class="field-section" ><h4>方案、适配与复验</h4><div class="field-grid">
                        <el-form-item label="方案／商品编号及版本依据"
                          ><el-input
                            v-model="sample.details.designSummary"
                            type="textarea"
                            placeholder="明确本轮采用的设计或供应商方案" autosize/></el-form-item
                        ><el-form-item label="适配范围"
                          ><SharedCompatiblePhoneSelector v-model="sample.details.phoneModelIds" :members="phones" :allowed-ids="effectivePhones" aria-label="样品适配范围" placeholder="选择本项目已确认机型"/></el-form-item
                        ><el-form-item label="本次改动"
                          ><el-input
                            v-model="sample.changes"
                            type="textarea" autosize/></el-form-item
                        ><el-form-item label="复验说明"
                          ><el-input
                            v-model="sample.retestResult"
                            type="textarea" autosize/></el-form-item
                        ><el-form-item label="样品名称"
                          ><el-input v-model="sample.title" /></el-form-item
                        ><el-form-item label="采用供应商报价"
                          ><el-select
                            v-model="sample.details.quoteEventId"
                            clearable
                            ><el-option
                              v-for="q in detail.events.filter(
                                (e) => e.kind === 'quote',
                              )"
                              :key="q.eventId"
                              :value="q.eventId"
                              :label="`${q.data.supplierName} · ${q.data.amount} ${q.data.currency}／${q.data.unit}`" /></el-select
                        ></el-form-item>
                      </div>
                      <el-button
                        v-if="canFinanceWrite"
                        @click="
                          openTask('quote', {
                            supplierCode: sample.details.supplierCode,
                            unit: '每件',
                          })
                        "
                        >保存报价版本</el-button
                      ></section></el-form
                ><el-button
                  v-if="editable && !sampleReadOnly"
                  type="primary"
                  :disabled="busy"
                  @click="saveClick(true)"
                  >{{stage.step===3?'登记实际收样':'确认收样与初检'}}</el-button
                >
                <p v-if="sampleReadOnly" class="muted">
                  {{stage.step===3?'本轮已收样，确认结论在下一步填写。':'本轮已确认；修改或复验请新增一轮。'}}
                </p></template
              >
              <template v-else-if="workKind === 'mold'">
                <h3 class="work-heading">{{stage.step===5?'供应商与模具设计':'逐套制作与试模'}}</h3>
                <el-form v-if="stage.step===5&&confirmedPlan.needsMold" class="step-fields" label-position="top" :disabled="!editable||busy"><el-form-item label="外部开模供应商" required data-readiness-target="supplier"><el-select :model-value="moldSupplier?.supplierCode" filterable :disabled="!canSelectSupplier" :placeholder="nextMoldSupplierPending?'下一家待定':'待确定'" @change="chooseMoldSupplier"><el-option v-for="o in suppliers" :key="o.supplierCode" :value="o.supplierCode" :label="o.supplierName"/></el-select><el-button v-if="editable&&moldSupplier&&detail.canChangeSupplier" link @click="openSupplierChange(moldSupplier)">更换供应商</el-button></el-form-item><el-form-item label="本次模具设计资料" required data-readiness-target="mold-design-files"><el-select v-model="stepForm.moldDesignReferences" multiple value-key="id" placeholder="选择已上传的设计资料"><el-option v-for="m in currentMaterials.filter(m=>m.type==='file')" :key="m.id" :value="{type:m.type,id:m.id}" :label="`${m.name} · ${m.versionNo}`"/></el-select></el-form-item><el-form-item label="供应商交期"><el-date-picker v-model="stepForm.supplierDueDate" value-format="YYYY-MM-DD"/></el-form-item></el-form>

                <template v-if="currentMold"
                  ><div class="toolbar">
                    <el-button @click="currentMold = null"
                      >返回进度列表</el-button
                    >
                    <h3>
                      {{ nodes[node.node] }} ·
                      {{ currentMold.assetNumber || currentMold.setName }}
                    </h3>
                  </div>
                  <p class="muted">
                    {{ currentMold.compatibleModels }} ·
                    {{ currentMold.manufacturerName || "制作方待核对" }} · 计划
                    {{
                      currentMold.plannedMoldDeliveryDate ||
                      plan.moldDate ||
                      "待定"
                    }}
                  </p>
                  <el-form
                    label-position="top"
                    :disabled="!editable || busy || node.node === 'ready'"
                    ><el-form-item label="实际日期"
                      ><el-date-picker
                        v-model="node.actualDate"
                        value-format="YYYY-MM-DD" /></el-form-item
                    ><el-form-item label="本次结果"
                      ><el-radio-group v-model="node.result"
                        ><template v-if="node.node === 'testing'"
                          ><el-radio value="passed">通过</el-radio
                          ><el-radio value="failed">需改模</el-radio></template
                        ><template v-else
                          ><el-radio value="complete">完成</el-radio
                          ><el-radio value="blocked">遇阻</el-radio></template
                        ></el-radio-group
                      ></el-form-item
                    ><el-form-item
                      v-if="node.node !== 'making' || node.result === 'blocked'"
                      :label="
                        node.node === 'testing'
                          ? node.result === 'failed'
                            ? '问题与处理说明'
                            : '实际验证说明'
                          : node.result === 'blocked'
                            ? '遇阻原因与处理计划'
                            : '本次改动'
                      "
                      ><el-input
                        v-model="node.note"
                        type="textarea"
                        :autosize="{minRows:2}" /></el-form-item></el-form
                  ><el-button
                    v-if="
                      editable &&
                      node.node !== 'ready' &&
                      (detail as any).canScheduleWrite
                    "
                    type="primary"
                    :disabled="busy"
                    @click="saveClick(true)"
                    >确认本节点</el-button
                  ><ResearchSnapshot
                    v-if="node.node === 'ready'"
                    :value="currentMold.development?.last"
                    @view="viewMaterial"
                /></template>
                <template v-else-if="!confirmedPlan.needsMold"
                  ><el-tag>本计划无需新模具</el-tag>
                  <p>保留已确认的设计方案与无需开模依据，随后仍需样品验证和试产。</p>
                  <el-form label-position="top" :disabled="!editable"
                    ><el-form-item data-readiness-target="no-mold" label="无需开模依据"
                      ><el-input
                        v-model="noMoldReason"
                        type="textarea"
                        :autosize="{minRows:2}"
                        placeholder="例如直接采用供应商现有成品及已确认方案" /></el-form-item></el-form
                  ><el-button v-if="editable" @click="confirmNoMold">核对无需开模依据</el-button>
                </template>
                <template v-else
                  ><article
                    v-for="m in molds"
                    :key="m.moldSetId"
                    class="mold-row" :data-readiness-target="`mold:${m.moldSetId}`"
                  >
                    <div>
                      <strong>{{
                        m.assetNumber || m.setName || `模具 #${m.moldSetId}`
                      }}</strong
                      ><small>{{ m.compatibleModels }}</small>
                    </div>
                    <div>
                      {{ moldProgress(m)
                      }}<small
                        >计划
                        {{
                          m.plannedMoldDeliveryDate || plan.moldDate || "待定"
                        }}
                        · 实际
                        {{ moldActualDate(m) }}</small
                      >
                    </div>
                    <div>
                      {{ m.manufacturerName || "制作方待核对"
                      }}<small>{{ m.ownerName || "负责人待核对" }}</small>
                    </div>
                    <div class="row-actions">
                      <el-button
                        v-if="
                          editable &&
                          (detail as any).canScheduleWrite &&
                          stage.step===6 && m.development?.node !== 'ready'
                        "
                        link
                        type="primary"
                        @click="openNode(m)"
                        >办理当前节点</el-button
                      ><el-button
                        v-if="
                          editable &&
                          (detail as any).canScheduleWrite &&
                          stage.step===6 && (m.development?.node === 'ready'||(!m.development?.node&&m.pcStatus==='ready'&&m.tpuStatus==='ready'))
                        "
                        link
                        @click="restartMold(m)"
                        >重新验模</el-button
                      ><el-button v-if="editable&&stage.step===5" link type="danger" @click="unlinkMold(m.moldSetId)">取消关联</el-button><el-button link @click="inspect(m, '模具进度与依据')"
                        >查看进度</el-button
                      >
                    </div>
                  </article>
                  <article
                    v-for="a in applications.filter((a) => !a.moldSetId)"
                    :key="a.applicationId"
                    class="business-row"
                  >
                    <strong>{{
                      a.approvalNo || `申请草稿 #${a.applicationId}`
                    }}</strong
                    ><span>原申请状态：{{ a.status }} · 尚未生成模具</span
                    ><el-button
                      link
                      @click="
                        router.push({
                          path: '/mold-applications',
                          query: {
                            applicationId: a.applicationId,
                          },
                        })
                      "
                      >查看原申请</el-button
                    >
                  </article>
                  <el-empty
                    v-if="!molds.length && !applications.length"
                    description="请关联原模具或原开模申请。"
                  /><section class="field-section" data-readiness-target="mold-links" v-if="editable && stage.step===5"><h4>关联模具／开模申请</h4><el-form label-position="top"><div class="field-grid">
                        <el-form-item label="已有模具"><el-select
                          v-model="moldChoice"
                          filterable
                          clearable
                          placeholder="选择兼容的已有模具"
                          @visible-change="$event && loadMoldChoices()"
                          ><el-option
                            v-for="m in moldChoices"
                            :key="m.moldSetId"
                            :value="m.moldSetId"
                            :label="`${m.physicalCode} · ${m.styleName} · ${m.modelName}`" /></el-select></el-form-item
                        ><el-form-item label="原开模申请"><el-select
                          v-model="applicationChoice"
                          clearable
                          placeholder="选择本项目原申请"
                          ><el-option
                            v-for="a in applications"
                            :key="a.applicationId"
                            :value="a.applicationId"
                            :label="
                              a.approvalNo || `原申请 #${a.applicationId}`
                            "
                        /></el-select></el-form-item>
                      </div>
                      <el-form-item label="复用确认"><el-checkbox v-model="reuseConfirmed">明确采用已有可用模具</el-checkbox></el-form-item><el-form-item v-if="reuseConfirmed" label="已有模具采用依据"><el-input v-model="reuseReason" placeholder="说明本次采用该模具的依据"/></el-form-item>
                      <el-button
                        :disabled="busy || (!moldChoice && !applicationChoice)"
                        @click="linkMold"
                        >确认关联</el-button
                      >
                      </el-form></section></template
                >
              </template>
              <template v-else
                ><h3 class="work-heading">试产依据与技术定型</h3><div class="basis-summary">
                  <p>
                    采用样品 S{{
                      adoptedSample?.roundNo || "待核对"
                    }}
                    · L{{ confirmedPlan.planVersion }} ·
                    {{
                      confirmedPlan.needsMold ? "核对实际验模" : "无需新模具"
                    }}
                  </p>
                  <el-button
                    link
                    @click="
                      inspect(
                        {
                          sample: workflow.validationBasis,
                          molds: (workflow.validationBasis as any)?.molds,
                        },
                        '当时采用方案',
                      )
                    "
                    >查看方案与模具依据</el-button
                  >
                </div>
                <el-form label-position="top" :disabled="!editable || busy"
                  ><el-form-item data-readiness-target="release-date" label="本次定型确认日期"
                    ><el-date-picker
                      v-model="release.confirmationDate"
                      value-format="YYYY-MM-DD" /></el-form-item
                  ><div class="field-grid"><el-form-item data-readiness-target="release-scope" label="定型适配范围"><SharedCompatiblePhoneSelector v-model="release.phoneModelIds" :members="phones" :allowed-ids="effectivePhones" aria-label="定型适配范围"/></el-form-item><el-form-item data-readiness-target="release-pilot" label="采用试产评审"><el-select v-model="release.pilotRoundId" @change="releasePilotChanged" placeholder="选择本方案通过的试产"><el-option v-for="r in detail.rounds.filter(r=>r.kind==='pilot'&&r.status==='confirmed'&&r.result==='passed'&&!r.voided)" :key="r.roundId" :value="r.roundId" :label="`${r.title} · P${r.roundNo}`"/></el-select></el-form-item><el-form-item data-readiness-target="release-cost" label="采用单件成本版本"><el-select v-model="release.unitCostVersionId" placeholder="选择已确认成本版本"><el-option v-for="v in detail.unitCostOptions?.filter(v=>!v.voidReason)||[]" :key="v.id" :value="v.id" :label="`C${v.id} · ${v.title} · ${v.bomVersion||'供方报价依据'}`"/></el-select></el-form-item></div><section class="field-section" ><h4>确认样与交接说明</h4><div class="field-grid">
                        <el-form-item label="确认样／供货说明"
                          ><el-input
                            v-model="release.sampleNote"
                            type="textarea" autosize/></el-form-item
                        ><el-form-item label="确认样存放位置"
                          ><el-input
                            v-model="release.sampleLocation" /></el-form-item
                        ><el-form-item label="补齐责任人"
                          ><el-select
                            v-model="release.responsibleUserId"
                            placeholder="默认项目负责人"
                            ><el-option
                              v-for="o in owners"
                              :key="o.userId"
                              :value="o.userId"
                              :label="o.name" /></el-select></el-form-item
                        ><el-checkbox v-model="release.documentationOnly"
                          >仅报告／文字更正，实际方案不变</el-checkbox
                        ><el-form-item
                          v-if="release.documentationOnly"
                          label="不影响原验证的依据"
                          ><el-input
                            v-model="release.documentationReason"
                            type="textarea"
                        autosize/></el-form-item></div></section></el-form>
                <div class="row-actions">
                  <el-button
                    v-if="false"
                    type="primary"
                    @click="publicationOpen = true"
                    >形成正式资料包</el-button
                  ><el-button
                    v-if="
                      published &&
                      stage.step === actualStage &&
                      parallelWritable &&
                      !workflow.revision
                    "
                    type="primary" :disabled="!detail.canLaunch"
                    @click="openTask('launch')"
                    >{{workflow.launched?'调整上市资料':'确认正式上市并纳入在售'}}</el-button
                  ><el-button
                    v-if="
                      published &&
                      stage.step === actualStage &&
                      parallelWritable &&
                      !workflow.revision
                    "
                    @click="openTask('start_revision')"
                    >发起修订</el-button
                  >
                </div>
                <div v-if="published&&detail.launchIssues?.length" class="launch-issues" role="status"><strong>上市前待补事项</strong><p v-for="issue in detail.launchIssues" :key="issue.code">{{issue.message}}<el-button v-if="issue.target&&(issue.target!=='product-identity'||detail.canMaintainProductIdentity)" link @click="locateEngineeringGap(issue.target)">去补齐</el-button></p></div>
                <p v-if="workflow.launched" class="muted">
                  实际上市：{{ (workflow.launched as any).actualDate }} ·
                  {{ (workflow.launched as any).channels }}
                </p>
                <p v-if="currentPackage">
                  当前正式包 {{ currentPackage.versionNo }} ·
                  {{ currentPackage.publishedAt
                  }}<el-button
                    link
                    @click="inspect(currentPackage, '当前正式包')"
                    >查看</el-button
                  >
                </p>
                <el-collapse v-if="published"
                  ><el-collapse-item title="补齐、移交与接收"
                    ><div
                      v-for="pack in detail.releases"
                      :key="pack.releaseId"
                      class="release-row"
                    >
                      <strong>{{ pack.versionNo }}</strong
                      ><el-button link @click="inspect(pack, '历史正式包')"
                        >查看原包</el-button
                      ><template v-if="!blocked && stage.step === actualStage"
                        ><template
                          v-for="gap in pack.missingItems"
                          :key="gap.code"
                          ><el-tag
                            v-if="
                              detail.events.some(
                                (e) =>
                                  e.kind === 'supplement' &&
                                  e.data.releaseId === pack.releaseId &&
                                  e.data.gapCode === gap.code,
                              )
                            "
                            type="success"
                            >已补齐：{{ gap.message }}</el-tag
                          ><template v-else
                            ><el-button
                              link
                              @click="
                                openTask('supplement', {
                                  releaseId: pack.releaseId,
                                  gapCode: gap.code,
                                })
                              "
                              >补齐：{{ gap.message }}</el-button
                            ><el-button
                              link
                              @click="
                                openTask('gap_plan', {
                                  releaseId: pack.releaseId,
                                  gapCode: gap.code,
                                })
                              "
                              >调整补齐计划</el-button
                            ></template
                          ></template
                        ><el-button
                          link
                          @click="
                            openTask('transfer', { releaseId: pack.releaseId })
                          "
                          >登记移交</el-button
                        ><el-button
                          link
                          @click="
                            openTask('receipt', { releaseId: pack.releaseId })
                          "
                          >登记接收</el-button
                        ></template
                      >
                    </div></el-collapse-item
                  ></el-collapse
                ></template
              >

              <SupplierFeedbackPanel ref="supplierFeedbackPanel" :records="detail.supplierFeedback||[]" :restricted="detail.supplierFeedbackRestricted" :next-supplier-pending="nextMoldSupplierPending" :suppliers="suppliers" :materials="currentMaterials" :assignments="((workflow.planDraft as Form)?.supplierAssignments||(workflow.plan as Form)?.supplierAssignments||[])" :editable="editable&&!!detail.canChangeSupplier&&!plan.suppliersRestricted" :busy="busy" :context="supplierFeedbackContext" :error="error" :default-supplier-code="moldSupplier?.supplierCode" @save="submitSupplierFeedback" @upload="prepareUpload()" @view="viewMaterial" @download="downloadMaterial"/>
              <EngineeringBasisPanel v-if="stage.step===7" :assignments="plan.supplierAssignments||[]" @update:assignments="plan.supplierAssignments=$event" :selected="keys" @update:selected="updateMaterialKeys" :materials="currentMaterials" :suppliers="suppliers" :product-specific-code="detail.productSpecificCode" :can-maintain-product-identity="!!detail.canMaintainProductIdentity" :persisted-assignments="((workflow.planDraft as Form)?.supplierAssignments||(workflow.plan as Form)?.supplierAssignments||[])" :can-change-supplier="editable&&!!detail.canChangeSupplier&&!plan.suppliersRestricted" @change-supplier="openSupplierChange" @identity="openProductIdentity" :summary="detail.engineering" :editable="materialsEditable" :assignments-editable="editable" :suppliers-restricted="!!plan.suppliersRestricted" :can-select-supplier="canSelectSupplier" :can-confirm="!!detail.canConfirmEngineering&&editable" :can-manage-supplier="user.hasPermission('supplier:view')" :busy="busy" v-model:reason="engineeringReason" @confirm="confirmEngineering" @engineering="openEngineering" @suppliers="openSupplierMaterials" @locate="locateEngineeringGap"/>
              <ResearchMaterialsPanel ref="materialsPanel" :materials="stage.step<7?currentMaterials.filter(m=>!['bom','process'].includes(m.type)):currentMaterials" :selected="keys" @update:selected="updateMaterialKeys" :editable="editable" :selectable="materialsEditable" :busy="busy" :hide-engineering="stage.step<7" @upload="prepareUpload" @files="prepareLocalUpload" @view="viewMaterial" @download="downloadMaterial" @remove="removeMaterial" @engineering="openEngineering" @refresh="action(reloadMaterials)"/>
              <section v-if="stage.step===1" class="work-section cost-plan-section"><div class="section-heading"><h3>成本与计划</h3><div v-if="canMoney" class="row-actions"><el-button link @click="openWorkspace('cost')">单件成本估算</el-button><el-button link @click="openSource('/development-budgets',{projectId:id,kind:'platform'})">预计费用／预算</el-button></div></div>
                <el-form class="step-fields" label-position="top" :disabled="!editable||busy">
                  <el-form-item label="初步材料／部件" class="full-field"><el-input v-model="plan.preliminaryMaterials" type="textarea" autosize placeholder="已知材质、部件和规格，未知留空"/></el-form-item>
                  <el-form-item v-if="canMoney" label="目标单件成本"><div class="inline-fields"><el-input-number v-model="profile.targetCost" :controls="false" :min="0" placeholder="待确认"/><el-select v-model="profile.currency"><el-option v-for="c in ['CNY','MXN','USD','EUR']" :key="c" :value="c"/></el-select></div></el-form-item>
                  <el-form-item label="预计周期"><el-input v-model="plan.estimatedDuration" placeholder="如约60天；待确认可留空"/></el-form-item>
                  <el-form-item label="目标完成日期"><el-date-picker v-model="profile.expectedDeliveryDate" value-format="YYYY-MM-DD" clearable/></el-form-item>
                  <el-form-item label="补充说明" class="full-field"><el-input ref="notesInput" v-model="profile.notes" type="textarea" autosize placeholder="待确定的内容、需要协调的事项"/></el-form-item>
                  <el-form-item v-if="workflow.requirement" label="修改原因" class="full-field" data-readiness-target="requirement-change"><el-input v-model="profile.changeReason" type="textarea" autosize placeholder="说明本次立项资料的修改原因"/></el-form-item>
                </el-form>
              </section>
            </div>
          </el-tab-pane>
          <el-tab-pane v-if="[6,7].includes(stage.step)" label="验证与结果" name="reviews"><div class="form-area"><DevelopmentReviewPanel ref="reviewPanel" :detail="detail" :stage="stage.step" :owners="owners" @updated="childUpdated" @view="viewMaterial"/></div></el-tab-pane>
        </el-tabs>
        </div>
        <footer class="save-bar" v-if="editable && tab==='work'">
          <span class="muted">{{
            dirty ? "有未保存输入" : "已载入保存内容"
          }}</span
          ><el-button
            v-if="stage.step!==4&&(workKind !== 'sample' || !sampleReadOnly)"
            :disabled="
              busy ||
              (workKind === 'mold' && node.node === 'ready' && !!currentMold)
            "
            @click="saveClick()"
            >保存草稿</el-button
          ><el-button
            v-if="!currentMold"
            type="primary"
            :disabled="busy||(stage.step===8&&!!timeline?.currentConfirmed&&!workflow.revision&&readinessChecks.every(c=>c.complete))"
            @click="complete"
            >{{
              stage.step===1?'确认立项，开始画图':stage.step<8?`完成本步，进入${PRODUCT_RESEARCH_STAGES.find(s=>s.step===stage.step+1)?.name}`:(timeline?.currentConfirmed&&!workflow.revision&&readinessChecks.every(c=>c.complete)?'发模已完成':'完成本项目发模')
            }}</el-button
          >
        </footer>
      </template>
    </template>
    <el-dialog class="research-entry-dialog" v-model="newOpen" title="新建新品" width="min(440px,94vw)"
      ><el-form label-position="top"
        ><el-form-item label="产品名称"
          ><el-input
            v-model="newName"
            maxlength="255"
            @keyup.enter="create" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="newOpen = false">取消</el-button
        ><el-button type="primary" :loading="busy" @click="create"
          >创建草稿</el-button
        ></template
      ></el-dialog
    >
    <el-dialog
      v-model="snapshotOpen"
      :title="snapshotTitle"
      width="min(760px,94vw)"
      append-to-body
      ><ResearchSnapshot :value="snapshot" @view="viewMaterial"
    /></el-dialog>
    <el-drawer v-model="engineeringOpen" :title="engineering==='bom'?'BOM 具体版本与用料':'工艺路线具体版本'" size="min(1400px,96vw)">
      <ProjectBomPanel v-if="engineering==='bom'&&editable" :project-id="id" @changed="reloadMaterials"/>
      <ProjectProcessRoutePanel v-else-if="engineering==='process'&&editable" :project-id="id" mes-graph @changed="reloadMaterials"/>
      <ResearchSnapshot v-else :value="currentMaterials.filter(m=>m.type===engineering)" @view="viewMaterial"/>
    </el-drawer>
    <el-dialog :model-value="!!previewFile" :title="previewFile?`${previewFile.originalFileName} · ${previewFile.versionNo}`:'资料预览'" width="min(900px,94vw)" destroy-on-close @update:model-value="!$event&&(previewFile=null)"><DocumentPreview v-if="previewFile" :file="previewFile"/></el-dialog>
    <el-dialog class="research-entry-dialog" v-model="uploadOpen" :title="uploadPrevious?'上传新版本':uploadImagesOnly?'上传产品图片':'上传产品附件'" width="min(660px,94vw)">
      <el-alert v-if="uploadError" :title="uploadError" type="error" :closable="false"/>
      <el-upload v-model:file-list="pendingFiles" drag :auto-upload="false" :multiple="!uploadPrevious" :limit="20" :accept="uploadImagesOnly?imageUploadAccept:ENGINEERING_UPLOAD_ACCEPT" :disabled="busy" :on-exceed="()=>ElMessage.warning('每次最多选择20份文件')"><div class="upload-drop"><strong>{{uploadImagesOnly?'拖放图片到这里，或点击选择图片':'拖放附件到这里，或点击选择文件'}}</strong><span>{{uploadPrevious?'为当前文件保留一个明确新版本':'支持多选；每份文件分别保存原始版本'}} · 单份最多 200 MB</span></div></el-upload>
      <el-form label-position="top" :disabled="busy" class="upload-metadata"><div class="field-grid"><el-form-item label="资料类别"><el-select v-model="fileForm.category" :disabled="!!uploadPrevious"><el-option v-for="c in FILE_CATEGORY_OPTIONS" :key="c.value" :value="c.value" :label="c.value==='sample_image'?'样品／实物照片':c.label"/></el-select></el-form-item><el-form-item label="具体版本"><el-input v-model="fileForm.versionNo" placeholder="如 D1、V2"/></el-form-item></div><el-form-item label="修改说明"><el-input v-model="fileForm.reason" type="textarea" :autosize="{minRows:2}"/></el-form-item></el-form>
      <template #footer><span class="muted">已成功 {{uploadedFileCount}} 份 · 待上传 {{pendingFiles.length}} 份</span><el-button :disabled="busy" @click="uploadOpen=false">关闭</el-button><el-button :loading="busy" type="primary" @click="upload">{{uploadedFileCount&&pendingFiles.length?'继续上传':'保存版本'}}</el-button></template>
    </el-dialog>
    <el-dialog
      class="research-entry-dialog"
      v-model="publicationOpen"
      title="核对正式包与资料缺口"
      width="min(520px,94vw)"
      ><p>
        {{ missing.length ? `尚缺：${missing.join("、")}` : "所需资料已选定" }}
      </p>
      <el-form label-position="top"
        ><el-form-item label="发布说明／例外原因"
          ><el-input
            v-model="publication.reason"
            type="textarea" autosize/></el-form-item
        ><el-form-item v-if="missing.length" label="补齐日期"
          ><el-date-picker
            v-model="publication.dueDate"
            value-format="YYYY-MM-DD" /></el-form-item
        ><el-checkbox v-if="missing.length" v-model="publication.risk"
          >确认上述资料缺口及补齐责任</el-checkbox
        ></el-form
      ><template #footer
        ><el-button :loading="busy" type="primary" @click="publish"
          >确认发布</el-button
        ></template
      ></el-dialog
    >
    <el-dialog
      class="research-entry-dialog"
      v-model="taskOpen"
      :title="eventNames[task] || '记录'"
      :before-close="beforeTaskClose"
      :close-on-click-modal="false"
      width="min(560px,94vw)"
      ><el-form label-position="top">
        <template v-if="task === 'supplier_change'">
          <p class="muted">原供应商：{{form.previousSupplierName}} · {{form.previousSupplierCode}}</p>
          <p class="muted">供货／制作范围：{{form.scope||'待补'}}。更换后需重新确认工程方案，历史评审及样品保留。</p>
          <el-form-item label="后续供应商"><el-checkbox v-model="form.replacementPending" :disabled="busy">下一家待定，结束原供应商对接</el-checkbox></el-form-item>
          <el-form-item v-if="!form.replacementPending" label="新供应商" required><el-select v-model="form.supplierCode" filterable clearable :disabled="busy" aria-label="新供应商" placeholder="选择已有供应商"><el-option v-for="supplier in suppliers" :key="supplier.supplierCode" :value="supplier.supplierCode" :label="`${supplier.supplierName} · ${supplier.supplierCode}`" :disabled="supplier.status==='inactive'||supplier.needsReview||supplier.supplierCode===form.previousSupplierCode"/></el-select></el-form-item>
          <el-form-item label="供应商更换原因" required><el-input v-model="form.reason" :disabled="busy" type="textarea" :autosize="{minRows:3}" maxlength="2000" show-word-limit aria-label="供应商更换原因" placeholder="说明原供方无法实现的技术要求，以及本次更换原因"/></el-form-item>
          <el-form-item v-if="!form.replacementPending" label="新供方采用依据（可后补）"><el-input v-model="form.basis" :disabled="busy" type="textarea" :autosize="{minRows:2}" maxlength="2000" aria-label="新供方采用依据" placeholder="新供方的沟通、报价或打样依据；尚未取得可留空"/></el-form-item>
          <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
        </template>
        <template v-else-if="task === 'link'"
          ><el-form-item label="名称"
            ><el-input v-model="form.title" /></el-form-item
          ><el-form-item label="链接"
            ><el-input
              v-model="form.url"
              placeholder="https://" /></el-form-item
        ></template>
        <template v-else-if="task === 'cost'"
          ><el-form-item label="记录类型"
            ><el-select v-model="form.type" :disabled="!!form.id"
              ><el-option label="项目总预算" value="budget" /><el-option
                label="手工费用"
                value="expense" /></el-select></el-form-item
          ><el-form-item label="名称"
            ><el-input v-model="form.name" /></el-form-item
          ><el-form-item v-if="form.type === 'expense'" label="费用类别"
            ><el-select v-model="form.category"
              ><el-option
                v-for="(name, key) in {
                  mold: '模具',
                  sample: '样品',
                  design: '设计',
                  promotion: '推广',
                  other: '其他',
                }"
                :key="key"
                :value="key"
                :label="name" /></el-select></el-form-item
          ><el-form-item label="金额与币种"
            ><el-input-number
              v-model="form.amount"
              :min="0"
              :precision="2" /><el-select
              v-model="form.currency"
              :disabled="!!form.id"
              ><el-option
                v-for="c in ['CNY', 'MXN', 'USD', 'EUR']"
                :key="c"
                :value="c" /></el-select></el-form-item
          ><section v-if="form.type==='expense'" class="field-section"><h4>费用发生与凭据</h4><el-form-item label="实际发生日期"
                ><el-date-picker
                  v-model="form.occurredAt"
                  value-format="YYYY-MM-DD" /></el-form-item
              ><el-form-item label="备注／原始凭据"
                ><el-input
                  v-model="form.notes"
                  type="textarea" autosize/></el-form-item></section>
          <p v-if="form.type === 'budget'" class="muted">
            同项目同币种沿用一份总预算；已存在时修改原预算并留存版本。模具分项在原预算台账维护。
          </p></template
        >
        <template v-else-if="task === 'promotion' || task === 'presale'"
          ><el-form-item label="渠道"
            ><el-select v-if="task === 'promotion'" v-model="form.channel"
              ><el-option
                v-for="(name, key) in channelNames"
                :key="key"
                :value="key"
                :label="name" /></el-select
            ><el-input v-else v-model="form.channel" /></el-form-item
          ><el-form-item
            :label="task === 'promotion' ? '具体账号／合作方' : '客户'"
            ><el-input v-model="form.target" /></el-form-item
          ><template v-if="task === 'promotion'"
            ><el-form-item label="推广动作"
              ><el-input v-model="form.action" /></el-form-item
            ><el-form-item label="计划日期"
              ><el-date-picker
                v-model="form.plannedDate"
                value-format="YYYY-MM-DD" /></el-form-item></template
          ><template v-else
            ><el-form-item label="本项目机型"
              ><SharedCompatiblePhoneSelector :model-value="form.phoneModelId ? [form.phoneModelId] : []" :multiple="false" :members="phones" :allowed-ids="effectivePhones" aria-label="本项目机型" @update:model-value="form.phoneModelId=$event[0] || null"/></el-form-item
            ><el-form-item label="数量"
              ><el-input-number
                v-model="form.quantity"
                :min="1"
                :precision="0" /></el-form-item
            ><el-form-item label="承诺交付日期"
              ><el-date-picker
                v-model="form.promisedDate"
                value-format="YYYY-MM-DD" /></el-form-item></template
          ><section class="field-section"><h4>执行进度与责任</h4><el-form-item label="状态"
                ><el-select v-model="form.status"
                  ><el-option
                    v-for="key in task === 'promotion'
                      ? ['pending', 'working', 'done', 'cancelled']
                      : ['reserved', 'confirmed', 'cancelled', 'fulfilled']"
                    :key="key"
                    :value="key"
                    :label="statusNames[key]" /></el-select></el-form-item
              ><el-form-item v-if="task === 'promotion'" label="实际执行日期"
                ><el-date-picker
                  v-model="form.actualDate"
                  value-format="YYYY-MM-DD" /></el-form-item
              ><el-form-item
                v-if="task === 'promotion' && canMoney"
                label="关联项目费用"
                ><el-select v-model="form.expenseId" clearable
                  ><el-option
                    v-for="r in finances.expenses"
                    :key="String(r.expenseId)"
                    :value="Number(r.expenseId)"
                    :label="`${r.description} · ${r.currencyCode} ${r.declaredAmount ?? '待定'}`" /></el-select></el-form-item
              ><template v-if="task === 'presale' && canFinanceWrite"
                ><el-form-item label="预售单价（选填）"
                  ><el-input-number
                    v-model="form.unitPrice"
                    :min="0" /><el-select v-model="form.currency"
                    ><el-option
                      v-for="c in ['CNY', 'MXN', 'USD', 'EUR']"
                      :key="c"
                      :value="c" /></el-select></el-form-item
                ><el-form-item label="订金说明"
                  ><el-input
                    v-model="form.depositNote"
                    placeholder="仅记录说明，实际收款沿用原财务凭据" /></el-form-item></template
              ><el-form-item label="实际反馈／客户要求"
                ><el-input v-model="form.notes" type="textarea" autosize/></el-form-item
              ><el-form-item label="采用素材／凭据版本"
                ><el-select v-model="form.references" multiple value-key="id"
                  ><el-option
                    v-for="m in currentMaterials"
                    :key="materialKey(m)"
                    :label="`${m.name} · ${m.versionNo}`"
                    :value="{
                      type: m.type,
                      id: m.id,
                    }" /></el-select></el-form-item></section></template>
        <template v-else-if="task === 'quote'"
          ><el-form-item label="供应商"
            ><el-select v-model="form.supplierCode" filterable
              ><el-option
                v-for="s in suppliers"
                :key="s.supplierCode"
                :value="s.supplierCode"
                :label="s.supplierName" /></el-select></el-form-item
          ><el-form-item label="金额"
            ><el-input-number v-model="form.amount" :min="0" /></el-form-item
          ><el-form-item label="币种"
            ><el-select v-model="form.currency"
              ><el-option
                v-for="c in ['CNY', 'MXN', 'USD', 'EUR']"
                :key="c"
                :value="c" /></el-select></el-form-item
          ><el-form-item label="计价单位"
            ><el-input v-model="form.unit" /></el-form-item
          ><el-form-item label="报价条件"
            ><el-input
              v-model="form.conditions"
              type="textarea"
              placeholder="数量、税费、运费等" autosize/></el-form-item
        ></template>
        <template v-else-if="task === 'launch'"
          ><el-alert v-if="!workflow.launched" type="info" :closable="false" title="首次确认正式上市后，本产品自动进入统一在售目录，开模申请的产品选项和覆盖数量同步更新。"/><el-alert v-else type="info" :closable="false" title="本次仅调整上市资料，保留产品当前在售／停售状态。"/><el-form-item label="实际上市日期" required
            ><el-date-picker
              v-model="form.actualDate"
              value-format="YYYY-MM-DD" /></el-form-item
          ><el-form-item label="实际上架／开售渠道"
            ><el-input v-model="form.channels" type="textarea" autosize/></el-form-item
          ><el-form-item label="供货说明"
            ><el-input
              v-model="form.supplyNote"
              type="textarea" autosize/></el-form-item
          ><el-form-item v-if="workflow.launched" label="调整原因"
            ><el-input v-model="form.reason" type="textarea" autosize/></el-form-item
        ></template>
        <template v-else-if="task === 'start_revision'"
          ><el-form-item label="修订原因"
            ><el-input v-model="form.reason" type="textarea" autosize/></el-form-item
          ><el-form-item label="影响范围"
            ><el-input v-model="form.impact" type="textarea" autosize/></el-form-item
          ><el-checkbox v-model="form.technical"
            >结构、材料或关键工艺发生变化，需要重新验证</el-checkbox
          ></template
        >
        <template v-else
          ><el-form-item
            :label="task === 'gap_plan' ? '新补齐日期' : '实际日期'"
            ><el-date-picker
              v-if="task === 'gap_plan'"
              v-model="form.dueDate"
              value-format="YYYY-MM-DD" /><el-date-picker
              v-else
              v-model="form.actualDate"
              value-format="YYYY-MM-DD" /></el-form-item
          ><template v-if="task === 'supplement'"
            ><el-form-item label="补齐资料版本"
              ><el-select v-model="form.references" multiple value-key="id"
                ><el-option
                  v-for="m in currentMaterials"
                  :key="materialKey(m)"
                  :label="`${m.name} · ${m.versionNo}`"
                  :value="{
                    type: m.type,
                    id: m.id,
                  }" /></el-select></el-form-item></template
          ><template v-else-if="task !== 'gap_plan'"
            ><el-form-item label="接收方／接收人"
              ><el-input v-model="form.recipient" /></el-form-item
            ><el-form-item v-if="task === 'receipt'" label="对应移交记录"
              ><el-select v-model="form.transferEventId"
                ><el-option
                  v-for="e in detail?.events.filter(
                    (e) =>
                      e.kind === 'transfer' &&
                      e.data.releaseId === form.releaseId,
                  )"
                  :key="e.eventId"
                  :value="e.eventId"
                  :label="`${e.data.recipient} · ${e.data.actualDate}`" /></el-select></el-form-item></template
          ><el-form-item v-if="task === 'gap_plan'" label="调整原因"
            ><el-input v-model="form.reason" type="textarea" autosize/></el-form-item
          ><el-form-item v-else label="实际说明／反馈"
            ><el-input v-model="form.note" type="textarea" autosize/></el-form-item
        ></template>
        <el-form-item v-if="form.id" label="本次调整原因"
          ><el-input v-model="form.reason" type="textarea"
        autosize/></el-form-item> </el-form
      ><template #footer
        ><el-button @click="taskOpen = false">取消</el-button
        ><el-button type="primary" :loading="busy" @click="submitTask"
          >保存记录</el-button
        ></template
      ></el-dialog
    >
  </section>
</template>

<style scoped>
.launch-page{height:100%;min-height:0;min-width:0;display:flex;flex-direction:column;gap:10px;overflow:hidden;container-type:inline-size;font-size:12px;--el-font-size-base:12px;--el-font-size-small:12px;--el-component-size:28px;--el-component-size-small:28px}
.launch-page,:global(.research-entry-dialog){--research-label-bg:#e9f0fa;--research-label-ink:#174776;--research-section-bg:#dce8f7;--research-field-bg:#fff;--research-field-ink:#192639;--research-field-border:#8da3bc;--research-focus:#245fc5;--research-placeholder:#697a91}
.research-mold-entry{border-bottom:1px solid var(--el-border-color-light);padding-bottom:8px}.research-mold-entry p{margin:0 0 4px}.research-application-row{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:4px 12px;min-width:0}.research-application-row>span{min-width:0;overflow-wrap:anywhere}
.toolbar,.row-actions,.filters,.workspace-links,.stage-context,.section-heading{display:flex;align-items:center;gap:8px;flex-wrap:wrap;min-width:0}
.toolbar>h2,.heading h2{font-size:16px;font-weight:700;line-height:1.5;margin:0}
h3,.work-heading{font-size:14px;font-weight:700;line-height:1.5;margin:0 0 12px}
.toolbar>h3{margin:0}
.toolbar>.el-input{flex:1;min-width:150px;max-width:500px}
.toolbar>.el-select{width:260px;max-width:100%}
.project-header{padding:4px 0 8px;gap:12px;flex:none}
.heading{flex:1;min-width:180px}
.heading h2{margin-bottom:4px;overflow-wrap:anywhere}
.heading small,small,.muted{color:var(--el-text-color-secondary);font-size:12px;line-height:1.6;overflow-wrap:anywhere}
.workspace-links{margin-left:auto;flex:none}
.workspace-links .el-button{margin-left:0}
.project-menu-arrow{margin-left:6px}
:global(.research-project-menu .el-dropdown-menu__item){font-size:12px;min-height:28px;line-height:20px;padding:4px 16px}
:global(.research-project-menu .delete-project-command){color:var(--el-color-danger)}
.stage-context{justify-content:space-between;flex:none}
.stage-context>.muted{flex:1;min-width:120px}
.research-step-nav{display:grid;grid-template-columns:repeat(8,minmax(0,1fr));flex:none;min-width:0;padding:14px 8px 0;background:var(--el-bg-color);border-bottom:1px solid var(--el-border-color-lighter)}
.research-step-nav button{position:relative;display:flex;flex-direction:column;align-items:center;gap:7px;min-width:0;padding:0 3px 12px;background:transparent;border:0;border-bottom:3px solid transparent;color:var(--el-text-color-secondary);font:inherit;line-height:1.5;overflow-wrap:anywhere;cursor:pointer}
.research-step-nav button:before{content:'';position:absolute;left:0;right:0;top:11px;height:1px;background:var(--el-border-color-lighter)}
.research-step-nav button:first-child:before{left:50%}
.research-step-nav button:last-child:before{right:50%}
.step-number{position:relative;z-index:1;display:grid;place-items:center;width:24px;height:24px;border-radius:50%;border:1px solid var(--el-border-color);background:var(--el-fill-color-light);font-weight:600;flex:none}
.research-step-nav .done{color:var(--el-color-success)}
.research-step-nav .done .step-number{border-color:var(--el-color-success);background:var(--el-bg-color)}
.research-step-nav .current{color:var(--el-color-primary);border-bottom-color:var(--el-color-primary);font-weight:700}
.research-step-nav .current .step-number{background:var(--el-color-primary);border-color:var(--el-color-primary);color:var(--el-color-white)}
.development-workspace{flex:1;min-height:0;min-width:0;overflow:auto;overflow-x:hidden;scrollbar-gutter:stable}
.stage-tabs{min-width:0;width:100%}
.stage-tabs :deep(.el-tabs__header){margin:0 0 10px}
.stage-tabs :deep(.el-tabs__content),.stage-tabs :deep(.el-tab-pane){overflow:visible;height:auto}
.single-work-tab :deep(.el-tabs__header){display:none}
.form-area{padding:0 18px 18px;min-width:0;width:100%;max-width:1080px;margin:0 auto;background:var(--el-bg-color);border:1px solid var(--el-border-color-lighter);border-radius:6px;container-type:inline-size}
.work-section{padding:18px 0;border-bottom:1px solid var(--el-border-color-lighter);min-width:0}
.work-section:last-child{border-bottom:0;padding-bottom:0}
.section-heading{justify-content:space-between;margin-bottom:12px}
.section-heading h3{margin:0}
.form-area :deep(.section-heading),.work-section>h3,.work-heading{padding:9px 12px;border-radius:4px;background:var(--research-section-bg);color:var(--research-label-ink)}
.form-area :deep(.section-heading h3){color:inherit;margin:0;font-weight:700}
.work-heading{margin-top:18px}
.form-area>.toolbar{padding-top:12px;margin-bottom:12px}
.form-area>.basis-summary{margin-top:0}
.step-fields,.field-grid,.sample-form{display:flex;flex-direction:column;gap:12px;min-width:0}
.inline-fields{display:flex;gap:6px;width:100%}
.inline-fields>.el-input-number{flex:1;min-width:0;width:auto}
.inline-fields>.el-select{width:90px;flex:none}
.field-section{padding-top:14px;margin-top:10px;border-top:1px solid var(--el-border-color-lighter);min-width:0}
.field-section h4{font-size:14px;font-weight:700;line-height:1.5;margin:0 0 12px}
.el-form-item :deep(.el-form-item__content){gap:6px;min-width:0}
.el-form-item :deep(.el-select),.el-date-editor,.el-input-number{max-width:100%;width:100%;min-width:0}
.el-checkbox{max-width:100%;height:auto;min-height:28px;white-space:normal}
.el-checkbox :deep(.el-checkbox__label){white-space:normal;overflow-wrap:anywhere}
.launch-page :deep(.el-button),.launch-page :deep(.el-input__inner),.launch-page :deep(.el-select__placeholder),.launch-page :deep(.el-form-item__label),.launch-page :deep(.el-radio__label),.launch-page :deep(.el-checkbox__label),.launch-page :deep(.el-tabs__item){font-size:12px}
.launch-page :deep(.el-button){margin-left:0;min-height:28px}
.launch-page :deep(.el-form-item){margin-bottom:12px}
.launch-page :deep(.el-form-item__label){line-height:20px;margin-bottom:4px}
.launch-page :deep(.el-input__wrapper),.launch-page :deep(.el-select__wrapper){min-height:28px}
.launch-page :deep(.el-textarea__inner){font-size:12px;line-height:1.7;min-height:28px}
.launch-page :deep(.el-collapse-item__header){min-height:32px;height:auto;line-height:1.6;padding:5px 0;font-size:12px}
.launch-page :deep(.el-collapse-item__content){padding-bottom:8px}
.launch-page :deep(.el-dialog__title){font-size:16px;font-weight:700}
.launch-page :deep(.el-dialog__body){max-height:68vh;overflow:auto}
.form-area :deep(.el-form),:global(.research-entry-dialog .el-form){display:flex;flex-direction:column;gap:12px;min-width:0}
.form-area :deep(.el-form-item),:global(.research-entry-dialog .el-form-item){display:flex;flex-direction:column;align-items:stretch;min-width:0;margin:0;border:1px solid var(--research-field-border);border-radius:5px;background:var(--research-field-bg);transition:border-color .15s,box-shadow .15s}
.form-area :deep(.el-form-item__label),:global(.research-entry-dialog .el-form-item__label){display:flex;align-items:center;gap:5px;flex-wrap:wrap;width:100%;height:auto;min-height:28px;margin:0;padding:4px 9px;border-radius:4px 4px 0 0;border-bottom:1px solid var(--el-border-color-lighter);background:var(--research-label-bg);color:var(--research-label-ink);font-size:12px;font-weight:700;line-height:20px;overflow-wrap:anywhere}
.form-area :deep(.el-form-item__label::after),:global(.research-entry-dialog .el-form-item__label::after){content:'正在填写' / '';visibility:hidden;flex:none;margin-left:auto;color:inherit;font-size:11px;font-weight:400;white-space:nowrap}
.form-area :deep(.el-form-item__content),:global(.research-entry-dialog .el-form-item__content){display:flex;flex-direction:column;align-items:stretch;gap:6px;min-width:0;margin-left:0;padding:0;border-radius:0 0 4px 4px;background:var(--research-field-bg)}
.form-area :deep(.el-form-item__content>.el-button),:global(.research-entry-dialog .el-form-item__content>.el-button){align-self:flex-start;margin:0 8px 4px}
.form-area :deep(.el-form-item__content>.el-checkbox),:global(.research-entry-dialog .el-form-item__content>.el-checkbox){padding:4px 9px}
.form-area :deep(.el-input__wrapper),.form-area :deep(.el-select__wrapper),.form-area :deep(.el-textarea__inner),:global(.research-entry-dialog .el-input__wrapper),:global(.research-entry-dialog .el-select__wrapper),:global(.research-entry-dialog .el-textarea__inner){background:var(--research-field-bg);color:var(--research-field-ink);font-size:12px;box-shadow:none}
.form-area :deep(.el-input__inner),.form-area :deep(.el-select__selected-item),:global(.research-entry-dialog .el-input__inner),:global(.research-entry-dialog .el-select__selected-item){color:var(--research-field-ink);font-size:12px}
.form-area :deep(.el-input__inner::placeholder),.form-area :deep(.el-textarea__inner::placeholder),.form-area :deep(.el-select__placeholder.is-transparent),:global(.research-entry-dialog .el-input__inner::placeholder),:global(.research-entry-dialog .el-textarea__inner::placeholder),:global(.research-entry-dialog .el-select__placeholder.is-transparent){color:var(--research-placeholder);opacity:1}
.form-area :deep(.el-form-item:focus-within),:global(.research-entry-dialog .el-form-item:focus-within){border-color:var(--research-focus);box-shadow:0 0 0 2px var(--el-color-primary-light-9)}
.form-area :deep(.el-form-item:focus-within>.el-form-item__label),:global(.research-entry-dialog .el-form-item:focus-within>.el-form-item__label){background:var(--research-focus);border-bottom-color:var(--research-focus);color:#fff}
.form-area :deep(.el-form-item:focus-within>.el-form-item__label::before),:global(.research-entry-dialog .el-form-item:focus-within>.el-form-item__label::before){color:inherit}
.form-area :deep(.el-form-item:focus-within>.el-form-item__label::after),:global(.research-entry-dialog .el-form-item:focus-within>.el-form-item__label::after){visibility:visible}
.form-area :deep(.el-form-item.is-error),:global(.research-entry-dialog .el-form-item.is-error){border-color:var(--el-color-danger)}
.form-area :deep(.el-form-item__error),:global(.research-entry-dialog .el-form-item__error){position:static;line-height:20px;padding:4px 9px;white-space:normal}
:global(.research-entry-dialog){font-size:12px;--el-font-size-base:12px;--el-component-size:28px}
:global(.research-entry-dialog .field-grid){display:flex;flex-direction:column;gap:12px}
:global(.research-entry-dialog .el-select),:global(.research-entry-dialog .el-input-number),:global(.research-entry-dialog .el-date-editor){width:100%;max-width:100%;min-width:0}
:global(.research-entry-dialog .el-input__wrapper),:global(.research-entry-dialog .el-select__wrapper){min-height:28px}
:global(.research-entry-dialog .el-textarea__inner){line-height:1.7}
:global(.research-entry-dialog .el-dialog__title){font-size:16px;font-weight:700}
:global(.research-entry-dialog .el-dialog__body){max-height:68vh;overflow:auto}
.save-bar{display:flex;gap:8px;align-items:center;justify-content:flex-end;flex-wrap:wrap;flex:none;border-top:1px solid var(--el-border-color-lighter);padding:10px 0 0;background:var(--el-bg-color-page)}
.save-bar .muted{margin-right:auto}
.save-bar .el-button{white-space:normal;height:auto;min-height:28px;max-width:100%;padding:6px 12px}
.save-bar .el-button--primary{font-weight:600}
.readonly-notice{background:var(--el-fill-color-light);padding:8px 12px;line-height:1.6;border-radius:4px;flex:none}
.list-scroll{flex:1;min-height:0;overflow:auto;overflow-x:hidden;background:var(--el-bg-color);border:1px solid var(--el-border-color-lighter);border-radius:6px}
.project-row,.business-row,.mold-row{display:grid;grid-template-columns:minmax(0,1.2fr) minmax(0,1fr) auto;gap:10px;align-items:center;padding:10px 12px;border-bottom:1px solid var(--el-border-color-lighter);overflow-wrap:anywhere;min-height:44px}
.project-row>div:first-child>strong{font-size:14px;font-weight:700}
.project-row small,.mold-row small{display:block;margin-top:3px}
.supplier-line{display:flex;align-items:center;flex-wrap:wrap;gap:2px 8px;min-width:0;margin-top:3px;font-size:12px}
.supplier-line>span{min-width:0;overflow-wrap:anywhere}
.supplier-feedback-summary{max-width:100%;height:auto;min-height:28px;white-space:normal;text-align:left;line-height:1.6;justify-content:flex-start;padding:0}
.supplier-feedback-summary :deep(span){white-space:normal;overflow-wrap:anywhere}
.mold-row{grid-template-columns:minmax(0,1fr) minmax(0,1fr) minmax(0,.8fr) auto}
.business-row .el-button{white-space:normal;text-align:left;height:auto;min-height:28px}
.release-row{padding:10px 0;display:flex;gap:8px;flex-wrap:wrap}
.el-alert{flex:none}
.summary-line{margin:8px 0}
.basis-summary{border-bottom:1px solid var(--el-border-color-lighter);margin-bottom:16px;padding-bottom:10px}
.relation-links{display:flex;flex-wrap:wrap;gap:8px;min-width:0}
.relation-links .el-button{margin:0;white-space:normal;height:auto;line-height:1.6;text-align:left;max-width:100%;overflow-wrap:anywhere}
.business-relations{margin-bottom:8px}
.business-relations :deep(.el-collapse-item__header){height:auto;min-height:36px;line-height:1.6;white-space:normal;overflow-wrap:anywhere}
.field-error{font-size:12px;color:var(--el-color-danger);line-height:20px;margin:0;flex-basis:100%}
.phone-pending-hint{font-size:12px;line-height:18px}
.upload-drop{display:flex;flex-direction:column;gap:6px;padding:8px;font-size:12px}
.upload-drop strong{font-weight:500}
.upload-drop span{color:var(--el-text-color-secondary)}
.upload-metadata{margin-top:12px}
.upload-metadata :deep(.el-select){width:100%}
.launch-issues{background:var(--el-color-warning-light-9);padding:10px 12px;margin:12px 0}
input[type="file"],.el-select{max-width:100%}
@container(max-width:850px){
 .research-step-nav{grid-template-columns:repeat(4,minmax(0,1fr));row-gap:10px}
 .research-step-nav button:nth-child(4):before{right:50%}.research-step-nav button:nth-child(5):before{left:50%}
 .mold-row,.business-row,.project-row{grid-template-columns:minmax(0,1fr) minmax(0,1fr)}
 .mold-row>.row-actions,.business-row>.row-actions,.business-row>.el-button,.project-row>.row-actions{grid-column:1/-1;justify-self:start}
}
@container(max-width:520px){
 .heading{min-width:0;flex-basis:100%}.workspace-links{margin-left:0}
 .toolbar>.el-input,.toolbar>.el-select{min-width:0;width:100%}
 .project-header>.el-button{padding-left:0}
 .launch-page .el-button{white-space:normal;max-width:100%;height:auto;min-height:28px}
 .project-row,.business-row,.mold-row{grid-template-columns:minmax(0,1fr)}
 .project-row,.business-row,.mold-row{gap:6px}
 .save-bar .muted{flex-basis:100%}.stage-context>.muted{flex-basis:100%}
 .form-area{padding:0 12px 14px}.work-section{padding:14px 0}
 .list-scroll :deep(.el-pagination){flex-wrap:wrap}
}
</style>
