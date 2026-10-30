import { DOMWrapper, mount, flushPromises, type VueWrapper } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import ElementPlus, {
  ElMessageBox,
  ElCheckbox,
  ElDropdownItem,
  ElOption,
  ElDatePicker,
  ElForm,
  ElRadioGroup,
  ElSelect,
  ElUpload,
  ElInput,
} from "element-plus";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ProductResearchView from "../ProductLaunchView.vue";
import DevelopmentReviewPanel from "../components/DevelopmentReviewPanel.vue";
import StageReadinessChecklist from "../components/StageReadinessChecklist.vue";
import ResearchMaterialsPanel from "../components/ResearchMaterialsPanel.vue";
import Product3dMaterials from "../components/Product3dMaterials.vue";
import ResearchMoldCoding from "../components/ResearchMoldCoding.vue";
import SupplierFeedbackPanel from "../components/SupplierFeedbackPanel.vue";
import EngineeringBasisPanel from "../components/EngineeringBasisPanel.vue";
import MoldApplicationFile from '@/views/mold-applications/components/MoldApplicationFile.vue';
import MoldApplicationDetailDrawer from '@/views/mold-applications/components/MoldApplicationDetailDrawer.vue';
import PhoneModelMultiSelect from '@/components/PhoneModelMultiSelect/index.vue';
import {authorizedBlobs} from '@/utils/authorizedBlobQueue';
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace';
import SharedCompatiblePhoneSelector from "@/views/mold/components/SharedCompatiblePhoneSelector.vue";
import {
  getPhoneModels,
  getPhoneModelOptions,
} from "@/api/modules/phoneReference";
import { deleteAttachment, uploadAttachmentVersion, uploadProjectAttachment } from "@/api/modules/attachment";
import type { CompatiblePhone } from "@/types/molds";
import { useUserStore } from "@/stores/user";
import {
  getProductResearch,
  deleteDevelopmentDraft,
  deleteProductResearchDraft,
  checkResearchDesign,
  checkResearchStage,
  getProductResearchDetail,
  saveDevelopmentRound,
  saveResearchDesignDraft,
  researchAction,
  getResearchOwnerOptions,
  type ProductResearchDetail,
  type ProductResearchRow,
  type SupplierFeedbackSummary,
} from "@/api/modules/productResearch";
import {
  getProjectTimeline,
  confirmTimelineNode,
  returnTimelineNode,
  type TimelineNodeVO,
} from "@/api/modules/project";
import { createProductResearch } from "@/api/modules/product";
import { PRODUCT_RESEARCH_STAGES } from "@/constants/productResearch";
import { archiveMoldFiles, downloadMoldFile, getMoldApplication, getResearchMoldApplicationContext, saveResearchMoldCoding, startResearchMoldApplication } from "@/api/modules/moldApplications";
const moldEditorOpen=vi.hoisted(()=>vi.fn());
const moldEditorOpenResearch=vi.hoisted(()=>vi.fn());
vi.mock('@/views/mold-applications/components/MoldApplicationEditor.vue',()=>({default:{name:'MoldApplicationEditor',setup(_:unknown,{expose}:{expose:(api:unknown)=>void}){expose({open:moldEditorOpen,openResearch:moldEditorOpenResearch});return()=>null;}}}));
vi.mock("@/api/modules/productResearch", async (original) => ({
  ...(await original<typeof import("@/api/modules/productResearch")>()),
  getProductResearch: vi.fn(),
  checkResearchDesign: vi.fn(),
  checkResearchStage: vi.fn(),
  getProductResearchDetail: vi.fn(),
  saveResearchDesignDraft: vi.fn(),
  saveDevelopmentRound: vi.fn(),
  deleteDevelopmentDraft: vi.fn(),
  deleteProductResearchDraft: vi.fn(),
  researchAction: vi.fn(),
  getResearchOwnerOptions: vi.fn(),
}));
vi.mock("@/api/modules/project", () => ({
  getProjectTimeline: vi.fn(),
  confirmTimelineNode: vi.fn(),
  returnTimelineNode: vi.fn(),
  publishProject: vi.fn(),
}));
vi.mock("@/api/modules/attachment", () => ({
  uploadProjectAttachment: vi.fn(),
  uploadAttachmentVersion: vi.fn(),
  deleteAttachment: vi.fn(),
  getAttachmentDetail: vi.fn(),
}));
vi.mock("@/api/modules/product", () => ({ createProductResearch: vi.fn() }));
vi.mock("@/api/modules/phoneReference", () => ({
  getPhoneSelectionBrands: vi.fn().mockResolvedValue([{id:1,brandId:1,name:"Apple",status:"enabled"}]),getPhoneModels: vi.fn().mockResolvedValue({ content: [] }),
  getPhoneModelOptions: vi.fn().mockResolvedValue([]),
  getPhoneCompatibleMolds: vi.fn().mockResolvedValue([]),
  getPhoneModelCoverage: vi.fn().mockResolvedValue([]),
}));
vi.mock("@/api/modules/supplier", () => ({
  getSupplierCenterSnapshot: vi.fn().mockResolvedValue({ suppliers: [] }),
}));
vi.mock("@/api/modules/moldApplications", async original => ({
  ...await original<typeof import('@/api/modules/moldApplications')>(),
  getMoldOptions:vi.fn().mockResolvedValue({processCode:'research',syncRunning:false,controls:[],configs:[],productLines:[],originators:[],canCreate:true,canConvert:false,administrator:false,canSubmit:false,writeEnabled:false,centralCodeEnabled:false}),
  getMoldApplications: vi.fn().mockResolvedValue({ content: [] }),
  getMoldTargetOptions: vi.fn().mockResolvedValue({ existingApplications: [] }),
  startResearchMoldApplication: vi.fn(),
  getResearchMoldApplicationContext: vi.fn(),
  getMoldApplication: vi.fn(),
  archiveMoldFiles: vi.fn(),
  saveResearchMoldCoding: vi.fn(),
  downloadMoldFile: vi.fn(),
  moldBusinessStatusNames:{draft:'草稿',awaiting_approval:'待 Tiffany 审核',approved:'Tiffany 已同意',rejected:'已拒绝',withdrawn:'已撤回',terminated:'已终止',approval_unknown:'审批待核对'},
}));
vi.mock("@/composables/useAttachmentViewer", () => ({
  useAttachmentViewer: () => ({ viewAttachment: vi.fn(), downloadFile: vi.fn() }),
}));
const record = (): ProductResearchDetail => ({
  productId: 42,
  productName: "轻量防摔壳",
  productCode: "RD-42",
  versionNo: "V1",
  model: "Phone Pro",
  requirements: "改善握持和按键手感",
  targetCost: 8.5,
  expectedDeliveryDate: null,
  ownerUserId: 1,
  ownerName: "研发负责人",
  status: "developing",
  canWrite: true,
  canPublish: true,
  currentStepNo: 1,
  workflow: { version: 2 },
  events: [],
  releases: [],
  rounds: [
    {
      roundId: 1,
      roundNo: 1,
      kind: "sample",
      recordDate: "2026-10-15",
      title: "第一轮按键验证",
      result: "failed",
      issues: "按键偏硬",
      changes: "",
      retestResult: "",
      status: "confirmed",
      confirmedBy: "研发负责人",
      details: { phoneModelIds: [1], makerName: "内部制作" },
      references: [
        {
          type: "file",
          id: 10,
          name: "结构图.pdf",
          versionNo: "D1",
          status: "draft",
          category: "drawing",
        },
      ],
    },
  ],
  materials: [
    {
      type: "file",
      id: 11,
      name: "结构图.pdf",
      versionNo: "D2",
      status: "draft",
      category: "drawing",
      familyId: 10,
    },
  ],
  release: null,
});
let wrapper: VueWrapper;
async function start(path = "/product-research?stage=1&productId=42",attachTo?:HTMLElement) {
  const pinia = createPinia();
  setActivePinia(pinia);
  const user = useUserStore();
  user.token = "test";
  user.permissions = ["*"];
  user.profile = {
    userId: 1,
    userName: "研发负责人",
    roleName: "工程",
    department: "研发",
  };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{path:'/mold-applications',component:{template:'<div>审批进度</div>'}},{path:'/mold-schedules',component:{template:'<div/>'}},{path:'/system/operation-log',component:{template:'<div/>'}},{path:"/development-budgets",component:{template:"<div/>"}},{path:'/products/:id/edit',component:{template:'<div/>'}},{ path: "/product-research", component: ProductResearchView },...['cost','promotion','presale'].map(kind=>({path:`/product-research-${kind==='cost'?'costs':kind==='promotion'?'promotions':'presales'}`,component:ProductResearchView,meta:{researchBusiness:kind}}))],
  });
  await router.push(path);
  await router.isReady();
  wrapper = mount(
    { template: "<router-view/>" },
    {
      ...(attachTo?{attachTo}:{}),
      global: {
        plugins: [pinia, router, ElementPlus],
        stubs: {
          ElDialog: {
            props: ["modelValue", "title"],
            template:
              '<section v-if="modelValue" class="test-dialog" :data-title="title"><slot/><slot name="footer"/></section>',
          },
          ElDrawer: {
            props: ["modelValue","title"],
            template: '<section v-if="modelValue"><slot/></section>',
          },
        },
      },
    },
  );
  await flushPromises();
  return router;
}
function button(
  text: string,
  scope: Pick<VueWrapper,'findAll'> | ReturnType<VueWrapper["get"]> = wrapper,
) {
  const found=scope.findAll("button").find((b) => b.text() === text);expect(found,`button ${text}`).toBeTruthy();return found!;
}
async function projectAction(label:string){
  await wrapper.get('button[aria-label="项目操作"]').trigger('click');await flushPromises();
  const item=wrapper.findAllComponents(ElDropdownItem).find(item=>item.text()===label);
  expect(item,`project action ${label}`).toBeTruthy();await item!.get('li[role="menuitem"]').trigger('click');await flushPromises();
}
async function tab(name: string) {
  await wrapper
    .findAll(".el-tabs__item")
    .find((t) => t.text() === name)!
    .trigger("click");
  await flushPromises();
}
beforeEach(() => {
  vi.clearAllMocks();
  moldEditorOpen.mockResolvedValue(undefined);
  moldEditorOpenResearch.mockResolvedValue(undefined);
  vi.mocked(getResearchMoldApplicationContext).mockResolvedValue(researchContext() as any);
  vi.mocked(getMoldApplication).mockResolvedValue({applicationId:88,productLineId:42,status:'draft',canEditDraft:true,form:{},files:[],activities:[]} as any);
  vi.mocked(checkResearchDesign).mockResolvedValue({version:2,ready:true,checks:[]});
  vi.mocked(checkResearchStage).mockResolvedValue({version:2,ready:true,checks:[]});
  vi.mocked(getPhoneModelOptions).mockResolvedValue([]);
  vi.mocked(getPhoneModels).mockResolvedValue({content:[]} as any);
  vi.mocked(getProductResearch).mockResolvedValue({
    content: [],
    page: 1,
    size: 50,
    totalElements: 0,
    totalPages: 0,
  });
  vi.mocked(getProductResearchDetail).mockResolvedValue(record());
  vi.mocked(saveResearchDesignDraft).mockResolvedValue(record());
  vi.mocked(researchAction).mockResolvedValue(record());
  vi.mocked(saveDevelopmentRound).mockResolvedValue({ ...record().rounds[0], roundId: 81, result: "pending" });
  vi.mocked(getResearchOwnerOptions).mockResolvedValue({
    owners: [{ userId: 1, name: "研发负责人" }],
  });
  vi.mocked(getProjectTimeline).mockResolvedValue({
    projectId: 42,
    currentNode: "需求立项",
    currentStepNo: 1,
    started: true,
    currentConfirmed: false,
    nodes: PRODUCT_RESEARCH_STAGES.map(s=>({stepNo:s.step,nodeKey:`RESEARCH_${s.key}`,nodeName:s.name} as TimelineNodeVO)),
  });
});
afterEach(() => {wrapper?.unmount();authorizedBlobs.clear();vi.restoreAllMocks();});

vi.mock("@/api/modules/development", () => ({
  getFinanceRows: vi.fn().mockResolvedValue({ content: [], totalElements: 0 }),
  getFinanceSummary: vi.fn().mockResolvedValue({ currencyGroups: [] }),
}));
const researchContext=()=>({applicationId:null,status:'draft',phoneModelId:null,productLineId:42,form:{model:null as string|null,remark:'改善握持和按键手感'},files:[],sourceSnapshot:{research:{projectId:42,productName:'轻量防摔壳',workflowVersion:2,phoneModelId:null,phoneModelIds:[],references:[],suppliers:[]}}});
const current = (step=1):any => ({
 ...record(),developmentMode:'launch_v1',currentStepNo:step,rounds:[],molds:[],applications:[],canScheduleWrite:true,moldApplicationEntry:{eligible:true,canCreate:true,phoneModelIds:step>1?[1]:[],reason:null},
 workflow:{version:2,phaseModel:'eight_step',requirementVersion:1,
  profileDraft:{productName:'轻量防摔壳',requirements:'改善按键',ownerUserId:1,phoneModelIds:[1]},
  planDraft:{phoneModelIds:[1],method:'supplier',needsMold:true,executionMode:'outsourced'},
  ...(step>1?{plan:{planVersion:1,phoneModelIds:[1],method:'supplier',needsMold:true,executionMode:'outsourced'}}:{}),
 },
});
async function launch(d=current(),shown=d.currentStepNo){
 vi.mocked(getProductResearchDetail).mockResolvedValue(d);vi.mocked(saveResearchDesignDraft).mockResolvedValue(d);vi.mocked(researchAction).mockResolvedValue(d);
 return start(`/product-research?step=${PRODUCT_RESEARCH_STAGES[shown-1]!.key}&productId=42`);
}
const nextButton=(step:number)=>step===1?'确认立项，开始画图':step===8?'完成本项目发模':`完成本步，进入${PRODUCT_RESEARCH_STAGES[step]!.name}`;
const fields=(name:string)=>wrapper.findAll('.el-form-item').find(f=>f.find('.el-form-item__label').exists()&&f.get('.el-form-item__label').text()===name)!;
const missing=(target:string,title:string)=>({code:target.toUpperCase(),title,target,complete:false,message:`请补齐${title}`});

describe('research application continuity and inline approval details',()=>{
 const refused=()=>({applicationId:88,version:4,productLineId:42,projectId:null,approvalNo:'APP-REFUSED',status:'completed',businessStatus:'rejected',terminal:true,phoneModelId:99,model:'Honor 600 lite',canEditDraft:false,refusal:{reason:'这个型号不好卖',actorName:'周汉琴',actorId:'verified',occurredAt:'2026-10-29T10:00:00+08:00'}});
 const nextPhone={id:2,phoneModelId:2,brandId:1,brandName:'Apple',modelName:'iPhone 18 Pro',status:'enabled',phoneModelCode:'1217',codeItemId:72,aliases:[]};
 function nextSource(){const source=researchContext();return {...source,phoneModelId:2,form:{...source.form,model:'iPhone 18 Pro'},activeApplications:[],historyApplications:[refused()],existingApplications:[],sourceSnapshot:{research:{...source.sourceSnapshot.research,phoneModelId:2,phoneModelName:'iPhone 18 Pro'}}};}
 it('shows current phones separately from the refused phone and opens its real detail in the list page',async()=>{
  const old=refused(),active={...old,applicationId:89,approvalNo:'APP-DRAFT',phoneModelId:2,model:'iPhone 18 Pro',status:'draft',businessStatus:'draft',terminal:false,refusal:null};
  const older={...old,applicationId:87,model:'旧手机',approvalNo:'OLDER-REFUSAL',refusal:{...old.refusal,reason:'过去的审批意见'}};
  vi.mocked(getProductResearch).mockResolvedValue({content:[{...record(),currentStepNo:2,currentStage:'画3D产品图',model:'--',phoneModels:[nextPhone],nextAction:'采用开发型号与3D图纸',moldApplicationSummary:{applications:[active,old,older],activeApplications:[active],historyApplications:[old,older],activeCount:1,historyCount:2,totalCount:3,canCreate:true}}],totalElements:1} as any);
  vi.mocked(getMoldApplication).mockResolvedValue({...old,sourceSystem:'dingtalk',form:{},files:[],activities:[]} as any);
  const router=await start('/product-research');const row=wrapper.get('.project-row');expect(row.text()).toContain('本次开发型号');expect(row.text()).toContain('iPhone 18 Pro');expect(row.text()).toContain('Honor 600 lite · 已拒绝');expect(row.text()).toContain('这个型号不好卖');expect(row.findAll('.approval-line')).toHaveLength(2);expect(row.text()).not.toContain('过去的审批意见');expect(getMoldApplication).not.toHaveBeenCalled();
  await button('查看原审批',row).trigger('click');await flushPromises();expect(getMoldApplication).toHaveBeenCalledWith(88);expect(router.currentRoute.value.fullPath).toBe('/product-research');expect(wrapper.getComponent(MoldApplicationDetailDrawer).text()).toContain('APP-REFUSED');expect(startResearchMoldApplication).not.toHaveBeenCalled();
 });
 it('selects another real phone after rejection and prepares the original editor without changing project or creating on open',async()=>{
  const d=current(2);d.applications=[refused()];vi.mocked(getPhoneModelOptions).mockResolvedValue([nextPhone] as any);const source=nextSource();vi.mocked(getResearchMoldApplicationContext).mockResolvedValue(source as any);const router=await launch(d);
  await button('选择其他型号',wrapper.get('[aria-label="LEMOS开模申请"]')).trigger('click');await flushPromises();expect(getResearchMoldApplicationContext).not.toHaveBeenCalled();
  const picker=wrapper.findAllComponents(PhoneModelMultiSelect).find(item=>item.props('ariaLabel')==='选择其他型号')!;expect(picker.exists()).toBe(true);picker.vm.$emit('update:modelValue',[2]);await flushPromises();await button('为所选型号填写申请').trigger('click');await flushPromises();
  expect(getResearchMoldApplicationContext).toHaveBeenCalledWith({projectId:42,version:2,phoneModelId:2});expect(moldEditorOpenResearch).toHaveBeenCalledWith(source,{projectInputUnsaved:false});expect(router.currentRoute.value.query).toEqual({step:'drawing',productId:'42'});expect(d.applications[0]).toEqual(refused());expect(startResearchMoldApplication).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();expect(saveResearchDesignDraft).not.toHaveBeenCalled();
 });
 it('opens an existing approval for the selected phone in the same page instead of preparing another one',async()=>{
  const d=current(2);d.applications=[refused()];vi.mocked(getPhoneModelOptions).mockResolvedValue([nextPhone] as any);const active={...refused(),applicationId:89,phoneModelId:2,model:'iPhone 18 Pro',status:'submitted',businessStatus:'awaiting_approval',terminal:false,refusal:null};
  vi.mocked(getResearchMoldApplicationContext).mockResolvedValue({...nextSource(),existingApplication:active} as any);vi.mocked(getMoldApplication).mockResolvedValue({...active,form:{},files:[],activities:[]} as any);const router=await launch(d);
  await button('选择其他型号',wrapper.get('[aria-label="LEMOS开模申请"]')).trigger('click');await flushPromises();wrapper.findAllComponents(PhoneModelMultiSelect).find(item=>item.props('ariaLabel')==='选择其他型号')!.vm.$emit('update:modelValue',[2]);await flushPromises();await button('为所选型号填写申请').trigger('click');await flushPromises();
  expect(getMoldApplication).toHaveBeenCalledWith(89);expect(router.currentRoute.value.path).toBe('/product-research');expect(wrapper.getComponent(MoldApplicationDetailDrawer).text()).toContain('iPhone 18 Pro');expect(moldEditorOpenResearch).not.toHaveBeenCalled();expect(startResearchMoldApplication).not.toHaveBeenCalled();
 });
});

describe('step-five coding uses the existing save action',()=>{
 function codingRecord(){const d=current(5);d.applications=[{applicationId:88,version:3,status:'submitted',businessStatus:'approved',model:'既有标准手机',canMaintainResearchCoding:true,authorization:{approverUserId:'real-tiffany',approverName:'周汉琴',taskId:'task-88'},researchCoding:{status:'pending',missingFields:['operationCode'],materialCodes:['10','20'],generatedMoldCodes:['MHA100030','MHA200030'],operationOptions:[{code:'4020',name:'既有最终工序'}]}}];return d;}
 it('keeps one coding block inside step five and saves a missing process with the latest application version',async()=>{
  const d=codingRecord();await launch(d)
  expect(wrapper.findAllComponents(ResearchMoldCoding)).toHaveLength(1)
  expect(wrapper.get('.project-header').text()).not.toContain('LEMOS')
  const panel=wrapper.getComponent(ResearchMoldCoding)
  panel.vm.$emit('update:inputs',{88:{operationCode:'4020'}});await flushPromises()
  vi.mocked(getMoldApplication).mockResolvedValue({...d.applications[0],version:9})
  vi.mocked(saveResearchMoldCoding).mockImplementation(async()=>{d.applications[0]={...d.applications[0],version:10,researchCoding:{...d.applications[0].researchCoding,status:'ready',missingFields:[],generatedProductCodes:['NHA4020003002']}};return d.applications[0]})
  await button('保存草稿').trigger('click');await flushPromises()
  expect(saveResearchMoldCoding).toHaveBeenCalledExactlyOnceWith(88,{version:9,operationCode:'4020'})
  expect(researchAction).toHaveBeenCalledWith(42,'launch_draft',2,expect.not.objectContaining({researchCodingInputs:expect.anything()}))
  expect(panel.text()).toContain('NHA4020003002');expect(panel.props('inputs')).toEqual({})
  expect(startResearchMoldApplication).not.toHaveBeenCalled();expect(confirmTimelineNode).not.toHaveBeenCalled()
 })
 it('retains missing-field input after a conflict and retries through the same save action',async()=>{
  const d=codingRecord();await launch(d)
  const panel=wrapper.getComponent(ResearchMoldCoding);panel.vm.$emit('update:inputs',{88:{operationCode:'4020'}});await flushPromises()
  vi.mocked(getMoldApplication).mockResolvedValue({...d.applications[0],version:9})
  vi.mocked(saveResearchMoldCoding).mockRejectedValueOnce(new Error('申请已更新，请刷新后保存编码资料')).mockImplementationOnce(async()=>{d.applications[0]={...d.applications[0],version:11,researchCoding:{status:'ready',missingFields:[],generatedMoldCodes:['MHA100030'],generatedProductCodes:['NHA4020003002']}};return d.applications[0]})
  await button('保存草稿').trigger('click');await flushPromises()
  expect(wrapper.text()).toContain('申请已更新');expect(panel.props('inputs')).toEqual({88:{operationCode:'4020'}})
  vi.mocked(getMoldApplication).mockResolvedValue({...d.applications[0],version:10})
  await button('保存草稿').trigger('click');await flushPromises()
  expect(vi.mocked(saveResearchMoldCoding).mock.calls.map(call=>call[1].version)).toEqual([9,10])
  expect(panel.props('inputs')).toEqual({});expect(startResearchMoldApplication).not.toHaveBeenCalled()
 })
 it('checks the current login before writing coding inputs returned by a late detail response',async()=>{
  const d=codingRecord();await launch(d)
  wrapper.getComponent(ResearchMoldCoding).vm.$emit('update:inputs',{88:{operationCode:'4020'}});await flushPromises()
  let finish!:(row:any)=>void;vi.mocked(getMoldApplication).mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
  await button('保存草稿').trigger('click');await flushPromises();useUserStore().token='another-login';finish({...d.applications[0],version:9});await flushPromises()
  expect(saveResearchMoldCoding).not.toHaveBeenCalled();expect(wrapper.text()).toContain('重新载入核对保存结果')
 })
})

describe('supplier feedback summary opens the existing project panel',()=>{
 const summary=(values:Partial<SupplierFeedbackSummary>={}):SupplierFeedbackSummary=>({cannotAcceptSupplierCount:2,nextSupplierPending:true,hasFeedback:true,restricted:false,...values});
 const row=(feedback=summary()):ProductResearchRow=>({productId:42,productName:'轻量防摔壳',productCode:'RD-42',model:'Phone Pro',status:'developing',currentStage:'开模',currentStepNo:6,ownerName:'研发负责人',roundCount:0,supplierNames:[],supplierFeedbackSummary:feedback});
 const listed=(feedback=summary())=>vi.mocked(getProductResearch).mockResolvedValue({content:[row(feedback)],totalElements:1,page:1,size:50,totalPages:1});
 function focusTracker(){
  const focused:HTMLElement[]=[];
  vi.spyOn(HTMLElement.prototype,'focus').mockImplementation(function(this:HTMLElement){if(this.getAttribute('aria-label')==='供应商反馈')focused.push(this);});
  return focused;
 }
 it('opens two failed suppliers at the actual project step and focuses their existing reasons and versioned attachments',async()=>{
  listed();const d=current(6);d.supplierFeedbackSummary=summary();d.supplierFeedback=[{feedbackId:88,eventId:88,revision:1,stage:1,supplierSnapshot:{supplierCode:'SUP-A',supplierName:'原技术供方'},result:'cannot_accept',reason:'PC倒扣无法实现',references:[d.materials[0]],recordedAt:'2026-10-23',recordedBy:'工程师'}];vi.mocked(getProductResearchDetail).mockResolvedValue(d);const focused=focusTracker();const router=await start('/product-research');
  const entry=wrapper.get('.supplier-feedback-summary');expect(entry.text()).toBe('2家无法承接 · 下一家待定');expect(entry.element.tagName).toBe('BUTTON');expect(entry.attributes('aria-label')).toContain('供应商反馈原因与附件');await entry.trigger('click');await flushPromises();
  expect(router.currentRoute.value.query).toEqual({productId:'42',step:'mold',focus:'supplier-feedback'});expect(focused).toHaveLength(1);expect(wrapper.get('[aria-label="供应商反馈"]').text()).toContain('PC倒扣无法实现');expect(wrapper.get('[aria-label="供应商反馈"]').text()).toContain('结构图.pdf · D2');expect(researchAction).not.toHaveBeenCalled();
  await button('返回列表').trigger('click');await flushPromises();expect(wrapper.get('.project-row').text()).toContain('2家无法承接 · 下一家待定');
 });
 it('keeps earlier failed suppliers visible after a next supplier is chosen without marking the row pending',async()=>{
  listed(summary({nextSupplierPending:false}));await start('/product-research');const entry=wrapper.get('.supplier-feedback-summary');expect(entry.text()).toBe('2家无法承接');expect(entry.classes()).not.toContain('el-button--warning');
 });
 it('uses neutral feedback wording for no active record and can show pending independently of failed counts',async()=>{
  listed(summary({cannotAcceptSupplierCount:0,hasFeedback:false,nextSupplierPending:false}));await start('/product-research');expect(wrapper.get('.supplier-feedback-summary').text()).toBe('暂无有效反馈');
  listed(summary({cannotAcceptSupplierCount:0,hasFeedback:true,nextSupplierPending:false}));await button('搜索').trigger('click');await flushPromises();expect(wrapper.get('.supplier-feedback-summary').text()).toBe('已记录反馈');
  listed(summary({cannotAcceptSupplierCount:0,hasFeedback:false,nextSupplierPending:true}));await button('搜索').trigger('click');await flushPromises();expect(wrapper.get('.supplier-feedback-summary').text()).toBe('下一家待定');
 });
 it('does not expose a restricted count or pending state or open its detail from the summary',async()=>{
  listed(summary({cannotAcceptSupplierCount:9,nextSupplierPending:true,restricted:true}));await start('/product-research');const entry=wrapper.get('.supplier-feedback-summary');expect(entry.text()).toBe('反馈受限');expect(entry.attributes('disabled')).toBeDefined();expect(entry.text()).not.toContain('9');await entry.trigger('click');await flushPromises();expect(getProductResearchDetail).not.toHaveBeenCalled();
 });
 it('keeps a direct focus link after restoring the actual step and does not refocus on later refresh',async()=>{
  vi.mocked(getProductResearchDetail).mockResolvedValue(current(7));const focused=focusTracker();const router=await start('/product-research?productId=42&focus=supplier-feedback');expect(router.currentRoute.value.query).toEqual({productId:'42',step:'pilot',focus:'supplier-feedback'});expect(focused).toHaveLength(1);
  window.dispatchEvent(new Event('focus'));await flushPromises();expect(focused).toHaveLength(1);
 });
 it('focuses the real feedback section and scrolls its inner workspace without another query navigation',async()=>{
  const host=document.createElement('div');document.body.appendChild(host);
  const rectangle=HTMLElement.prototype.getBoundingClientRect;
  vi.spyOn(HTMLElement.prototype,'getBoundingClientRect').mockImplementation(function(this:HTMLElement){
   if(this.classList.contains('development-workspace'))return new DOMRect(0,100,800,500);
   if(this.getAttribute('aria-label')==='供应商反馈')return new DOMRect(0,691,800,300);
   return rectangle.call(this);
  });
  try{
   vi.mocked(getProductResearchDetail).mockResolvedValue(current(6));const router=await start('/product-research?productId=42&step=mold&focus=supplier-feedback',host);
   const section=wrapper.get('[aria-label="供应商反馈"]').element,container=wrapper.get('.development-workspace').element;
   expect(router.currentRoute.value.query.focus).toBe('supplier-feedback');expect(getProductResearchDetail).toHaveBeenCalledTimes(1);expect(container.scrollTop).toBe(591);expect(document.activeElement).toBe(section);expect(section.getAttribute('tabindex')).toBe('-1');
  }finally{host.remove();}
 });
 it('keeps the old project hidden on a failed link and focuses only after retry loads the requested project',async()=>{
  const router=await launch(current(1));const focused=focusTracker();vi.mocked(getProductResearchDetail).mockRejectedValueOnce(Error('项目查询暂时失败'));await router.push('/product-research?productId=43&step=drawing&focus=supplier-feedback');await flushPromises();expect(wrapper.text()).toContain('项目查询暂时失败');expect(wrapper.find('[aria-label="供应商反馈"]').exists()).toBe(false);expect(focused).toHaveLength(0);expect(router.currentRoute.value.query.focus).toBe('supplier-feedback');
  const d=current(2);d.productId=43;d.productName='应定位的新项目';vi.mocked(getProductResearchDetail).mockResolvedValue(d);await button('重新载入').trigger('click');await flushPromises();expect(wrapper.get('.project-header').text()).toContain('应定位的新项目');expect(focused).toHaveLength(1);expect(router.currentRoute.value.query).toEqual({productId:'43',step:'drawing',focus:'supplier-feedback'});
 });
 it('does not focus or install an in-flight detail from the previous login identity',async()=>{
  let finish:((value:ProductResearchDetail)=>void)|undefined;vi.mocked(getProductResearchDetail).mockImplementation(()=>new Promise(resolve=>{finish??=resolve;}));const focused=focusTracker();const router=await start('/product-research?productId=42&step=initiation&focus=supplier-feedback');useUserStore().token='new-session';finish!(current(1));await flushPromises();expect(focused).toHaveLength(0);expect(wrapper.find('.project-header').exists()).toBe(false);expect(router.currentRoute.value.query.focus).toBe('supplier-feedback');
 });
 it('retains an unsaved feedback dialog when a focus-link navigation is cancelled',async()=>{
  const d=current(1);d.canChangeSupplier=true;const router=await launch(d);await button('记录反馈').trigger('click');await flushPromises();await wrapper.get('textarea[aria-label="反馈说明"]').setValue('仍需保留的技术反馈');vi.spyOn(ElMessageBox,'confirm').mockRejectedValueOnce('cancel');await router.push('/product-research?productId=42&step=initiation&focus=supplier-feedback');await flushPromises();expect(router.currentRoute.value.query.focus).toBeUndefined();expect((wrapper.get('textarea[aria-label="反馈说明"]').element as HTMLTextAreaElement).value).toBe('仍需保留的技术反馈');expect(researchAction).not.toHaveBeenCalled();
 });
 it('immediately suppresses server pending state for an unsaved local mold supplier selection',async()=>{
  const d=current(1);d.canChangeSupplier=true;d.supplierFeedbackSummary=summary();vi.mocked(getResearchOwnerOptions).mockResolvedValue({owners:[{userId:1,name:'研发负责人'}],canSelectSupplier:true,suppliers:[{supplierId:3,supplierCode:'SUP-C',supplierName:'下一家供方'}]});await launch(d);expect(wrapper.getComponent(SupplierFeedbackPanel).props('nextSupplierPending')).toBe(true);wrapper.get('[data-readiness-target="supplier"]').getComponent(ElSelect).vm.$emit('change','SUP-C');await flushPromises();expect(wrapper.getComponent(SupplierFeedbackPanel).props('nextSupplierPending')).toBe(false);expect(researchAction).not.toHaveBeenCalled();
 });
});

describe('one project and eight steps',()=>{
 it('lists projects fifty at a time without filtering them into four disconnected stages',async()=>{
  await start('/product-research');expect(getProductResearch).toHaveBeenCalledWith({keyword:'',state:'active',page:1,size:50,flow:'launch_v1'});
 });
 it('opens the actual current step when a project URL omits a step',async()=>{
  vi.mocked(getProductResearchDetail).mockResolvedValue(current(6));await start('/product-research?productId=42');
  expect(wrapper.get('.research-step-nav .current').text()).toBe('6开模');
 });
 it.each([1,7])('normalizes a step-less project URL to actual step %s and hydrates its saved fields',async step=>{
  const d=current(step);if(step===7)d.workflow.releaseDraft={confirmationDate:'2026-10-23',references:[{type:'file',id:11}]};vi.mocked(getProductResearchDetail).mockResolvedValue(d);const router=await start('/product-research?productId=42');
  expect(router.currentRoute.value.query).toEqual({productId:'42',step:PRODUCT_RESEARCH_STAGES[step-1]!.key});
  if(step===1)expect((wrapper.get('[data-readiness-target="product-name"] input').element as HTMLInputElement).value).toBe('轻量防摔壳');
  else expect(wrapper.get('[data-readiness-target="release-date"]').getComponent(ElDatePicker).props('modelValue')).toBe('2026-10-23');
  expect(saveResearchDesignDraft).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();expect(confirmTimelineNode).not.toHaveBeenCalled();
 });
 it('preserves an explicit earlier step as read-only and restores the actual step through its existing link',async()=>{
  const router=await launch(current(7),2);expect(router.currentRoute.value.query.step).toBe('drawing');expect(wrapper.findComponent(ElForm).props('disabled')).toBe(true);await button('前往当前阶段').trigger('click');await flushPromises();expect(router.currentRoute.value.query.step).toBe('pilot');expect(wrapper.get('[data-readiness-target="release-date"] input').attributes('disabled')).toBeUndefined();
 });
 it('opens a row at its actual current step from the unified project list',async()=>{
  const d=current(6);vi.mocked(getProductResearchDetail).mockResolvedValue(d);vi.mocked(getProductResearch).mockResolvedValue({content:[{productId:42,productName:'轻量防摔壳',productCode:'RD-42',currentStepNo:6,currentStage:'开模',ownerName:'研发负责人',supplierNames:['实际开模供方'],nextAction:'登记逐套制作与试模'}],totalElements:1,page:1,size:50,totalPages:1} as any);
  const router=await start('/product-research');expect(wrapper.get('.project-row').text()).toContain('登记逐套制作与试模');await button('查看／办理').trigger('click');await flushPromises();expect(router.currentRoute.value.query).toEqual({productId:'42',step:'mold'});expect(wrapper.get('.research-step-nav .current').text()).toBe('6开模');
 });
 it.each([[1,1],[2,5],[3,6],[4,7]])('keeps legacy stage %s links read-only compatible with new step %s',async(legacy,step)=>{
  vi.mocked(getProductResearchDetail).mockResolvedValue(current(step));await start(`/product-research?stage=${legacy}&productId=42`);
  expect(wrapper.get('.research-step-nav .current').text()).toBe(`${step}${PRODUCT_RESEARCH_STAGES[step-1]!.name}`);
 });
 it('creates a draft then navigates after the save guard releases',async()=>{
  vi.mocked(createProductResearch).mockResolvedValue({productId:88} as any);const router=await start('/product-research');
  await button('新建新品').trigger('click');await flushPromises();await wrapper.get('.test-dialog input').setValue('浏览器验收新品');await button('创建草稿').trigger('click');await flushPromises();
  expect(router.currentRoute.value.query).toEqual({step:'initiation',productId:'88'});
 });
 it('keeps initiation limited to current knowledge with visible costs, supplier and attachment entry points',async()=>{
  await launch();expect(wrapper.get('.research-step-nav').findAll('button').map(b=>b.text())).toEqual(PRODUCT_RESEARCH_STAGES.map(s=>`${s.step}${s.name}`));
  expect(wrapper.find('.stage-tabs').classes()).toContain('single-work-tab');expect(wrapper.text()).not.toContain('本阶段办理');
  expect(wrapper.findComponent(DevelopmentReviewPanel).exists()).toBe(false);expect(wrapper.findComponent(EngineeringBasisPanel).exists()).toBe(false);expect(wrapper.findComponent(SharedCompatiblePhoneSelector).exists()).toBe(false);
  for(const label of ['开发需求','初步材料／部件','预计周期','目标单件成本','意向开模供应商'])expect(wrapper.text()).toContain(label);
  for(const label of ['开发历史','设计评审','采用BOM版本','采用工艺路线版本','更多信息'])expect(wrapper.get('.development-workspace').text()).not.toContain(label);
  expect(button('单件成本估算').exists()).toBe(true);expect(button('预计费用／预算').exists()).toBe(true);expect(wrapper.get('.stage-materials').text()).toContain('从本地上传');
 });
 it('saves incomplete estimates, long requirements, notes and existing file IDs without future evidence',async()=>{
  await launch();await wrapper.get('[data-readiness-target="product-name"] input').setValue('修改的新品名称');await wrapper.get('[data-readiness-target="requirements"] textarea').setValue('结构需求\n保留原始性能要求');
  await fields('补充说明').get('textarea').setValue('原始资料\n未知项留空');await wrapper.get('.material-row input[type=checkbox]').setValue(true);
  await button('保存草稿').trigger('click');await flushPromises();
  expect(saveResearchDesignDraft).toHaveBeenCalledWith(42,expect.objectContaining({productName:'修改的新品名称',requirements:'结构需求\n保留原始性能要求',notes:'原始资料\n未知项留空',references:[{type:'file',id:11}],version:2}),expect.objectContaining({method:'supplier',executionMode:'outsourced'}));
  expect(researchAction).not.toHaveBeenCalled();expect(confirmTimelineNode).not.toHaveBeenCalled();
 });
 it('requires server readiness before advancing and keeps incomplete work available as draft',async()=>{
  const alert=vi.spyOn(ElMessageBox,'alert').mockResolvedValue('confirm' as any);const confirm=vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as any);
  vi.mocked(checkResearchDesign).mockResolvedValue({version:2,ready:false,checks:[missing('requirements','开发需求'),missing('owner','项目负责人')]});
  await launch();await button(nextButton(1)).trigger('click');await flushPromises();
  expect(alert).toHaveBeenCalledWith(expect.stringContaining('开发需求'),'本步缺项');expect(confirm).not.toHaveBeenCalled();expect(confirmTimelineNode).not.toHaveBeenCalled();
  await vi.waitFor(()=>expect(wrapper.get('[data-readiness-target="owner"] .el-form-item__error').text()).toBe('请补齐项目负责人'));
 });
 it.each([2,3])('completes step %s with a server-deferred 3D drawing without adopting files or skipping the confirmation action',async(step)=>{
  const d=current(step);d.workflow.stage2Basis={references:[]};if(step===3)d.rounds=[{...record().rounds[0],roundId:81,status:'confirmed',result:'pending',details:{samplePurpose:'prototype_3d',phoneModelIds:[1]}}];vi.mocked(checkResearchStage).mockResolvedValue({version:2,ready:true,checks:[{code:step===2?'PRODUCT_3D_DRAWING':'PROTOTYPE_DRAWING',title:'3D产品图',target:'drawings',complete:false,blocking:false,message:'3D图可后补，不影响本步'}]});const alert=vi.spyOn(ElMessageBox,'alert').mockResolvedValue('confirm' as any);vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as any);const router=await launch(d);expect(confirmTimelineNode).not.toHaveBeenCalled();await button(nextButton(step)).trigger('click');await flushPromises();expect(alert).not.toHaveBeenCalled();expect(confirmTimelineNode).toHaveBeenCalledWith(42,`RESEARCH_${PRODUCT_RESEARCH_STAGES[step-1]!.key}`,`完成${PRODUCT_RESEARCH_STAGES[step-1]!.name}`,2);expect(router.currentRoute.value.query.step).toBe(PRODUCT_RESEARCH_STAGES[step]!.key);if(step===2)expect(researchAction).toHaveBeenCalledWith(42,'launch_draft',2,expect.objectContaining({stage:2,references:[]}));expect(uploadProjectAttachment).not.toHaveBeenCalled();expect(startResearchMoldApplication).not.toHaveBeenCalled();
 });
 it('still blocks missing representative phones when the 3D drawing alone is deferred',async()=>{
  vi.mocked(checkResearchStage).mockResolvedValue({version:2,ready:false,checks:[{code:'PRODUCT_3D_DRAWING',title:'3D产品图',target:'drawings',complete:false,blocking:false,message:'3D图可后补，不影响本步'},missing('phones','本次开发型号')]});const alert=vi.spyOn(ElMessageBox,'alert').mockResolvedValue('confirm' as any),confirm=vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as any);await launch(current(2));await button(nextButton(2)).trigger('click');await flushPromises();expect(alert).toHaveBeenCalledWith('请补齐：请补齐本次开发型号','本步缺项');expect(confirm).not.toHaveBeenCalled();expect(confirmTimelineNode).not.toHaveBeenCalled();
 });
 it('confirms initiation with optional dates and the owner dropdown still loading',async()=>{
  const d=current();delete d.workflow.planDraft.phoneModelIds;d.workflow.profileDraft.phoneModelIds=[];
  vi.mocked(getResearchOwnerOptions).mockReturnValue(new Promise(()=>{}));vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as any);
  const router=await launch(d);await button(nextButton(1)).trigger('click');await flushPromises();
  expect(confirmTimelineNode).toHaveBeenCalledWith(42,'RESEARCH_initiation','完成立项',2);expect(router.currentRoute.value.query).toEqual({step:'drawing',productId:'42'});
  expect(researchAction).not.toHaveBeenCalledWith(42,'confirm_design_basis',expect.anything(),expect.anything());
 });
 it('walks the same project through all eight handovers without creating another project or sample confirmation flow',async()=>{
  let d=current(1);d.rounds=[{...record().rounds[0],roundId:81,status:'confirmed',result:'pending',details:{samplePurpose:'prototype_3d',phoneModelIds:[1]}},{...record().rounds[0],roundId:82,status:'confirmed',result:'passed',details:{samplePurpose:'mold_trial',phoneModelIds:[1]}}];
  d.molds=[{moldSetId:7,assetNumber:'MBA200007',actualShipDate:'2026-10-23',development:{node:'ready',version:3}}];
  vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as any);
  vi.mocked(getProductResearchDetail).mockImplementation(async()=>d);vi.mocked(saveResearchDesignDraft).mockImplementation(async()=>d);vi.mocked(researchAction).mockImplementation(async()=>d);
  vi.mocked(confirmTimelineNode).mockImplementation(async()=>{d={...d,currentStepNo:Math.min(8,d.currentStepNo+1),workflow:{...d.workflow,plan:d.workflow.plan||d.workflow.planDraft,version:d.workflow.version+1}};return {} as any;});
  const router=await start('/product-research?productId=42');
  for(let step=1;step<=8;step++){
   expect(wrapper.get('.research-step-nav .current').text()).toBe(`${step}${PRODUCT_RESEARCH_STAGES[step-1]!.name}`);
   await button(nextButton(step)).trigger('click');await flushPromises();
   expect(confirmTimelineNode).toHaveBeenNthCalledWith(step,42,`RESEARCH_${PRODUCT_RESEARCH_STAGES[step-1]!.key}`,`完成${PRODUCT_RESEARCH_STAGES[step-1]!.name}`,step+1);
   expect(router.currentRoute.value.query.productId).toBe('42');
  }
  expect(createProductResearch).not.toHaveBeenCalled();expect(startResearchMoldApplication).not.toHaveBeenCalled();expect(saveDevelopmentRound).not.toHaveBeenCalled();
 });
 it('uses one system log entrance carrying the real project ID',async()=>{
  const router=await launch();await projectAction('系统日志');expect(router.currentRoute.value.path).toBe('/system/operation-log');expect(router.currentRoute.value.query).toEqual({projectId:'42'});expect(wrapper.text()).not.toContain('开发历史');
 });
});

describe('sample confirmation returns reuse the original timeline transaction',()=>{
 const failedConfirmation=()=>{
  const d=current(4);d.workflow.prototypeRoundId=81;d.rounds=[{...record().rounds[0],roundId:81,status:'confirmed',result:'pending',details:{samplePurpose:'prototype_3d',phoneModelIds:[1],makerName:'真实打样供应商'}}];
  d.workflow.reviewRecords=[{id:91,gate:'design',status:'confirmed',result:'failed',title:'样品确认未通过',sampleRoundId:81,references:d.rounds[0].references}];return d;
 };
 it.each([['返回画图',2],['重新3D打样',3]])('returns through %s with the explicit target and a mandatory reason, preserving the received sample',async(label,target)=>{
  const d=failedConfirmation();const prompt=vi.spyOn(ElMessageBox,'prompt').mockResolvedValue({value:'按键行程不足，修改结构后重新验证'} as any);
  vi.mocked(getProjectTimeline).mockResolvedValue({projectId:42,currentStepCode:'RESEARCH_sample-confirm',currentStepNo:4,nodes:PRODUCT_RESEARCH_STAGES.map(s=>({stepNo:s.step,nodeKey:`RESEARCH_${s.key}`,nodeName:s.name}))} as any);
  vi.mocked(returnTimelineNode).mockImplementationOnce(async()=>{d.currentStepNo=target as number;return {} as any;});const router=await launch(d);expect(wrapper.find('.sample-form').exists()).toBe(false);await button(label as string).trigger('click');await flushPromises();
  expect(returnTimelineNode).toHaveBeenCalledWith(42,'RESEARCH_sample-confirm','按键行程不足，修改结构后重新验证',true,undefined,2,target);expect(router.currentRoute.value.query).toEqual({productId:'42',step:PRODUCT_RESEARCH_STAGES[(target as number)-1]!.key});
  const validator=prompt.mock.calls[0]![2]!.inputValidator as (value:string)=>unknown;expect(validator('')).toBe('请填写原因');expect(validator('   ')).toBe('请填写原因');expect(validator('结构需调整')).toBe(true);
  expect(saveDevelopmentRound).not.toHaveBeenCalled();expect(saveResearchDesignDraft).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();expect(d.rounds[0]).toMatchObject({roundId:81,status:'confirmed',result:'pending',recordDate:'2026-10-15'});
  if(target===3){expect(wrapper.getComponent(ElForm).props('disabled')).toBe(true);expect(wrapper.findAll('button').some(b=>b.text()==='删除样品草稿')).toBe(false);}
 });
 it.each(['返回画图','重新3D打样'])('cancels %s without changing the timeline or historical sample',async label=>{
  const d=failedConfirmation();vi.spyOn(ElMessageBox,'prompt').mockRejectedValueOnce('cancel');const router=await launch(d);await button(label).trigger('click');await flushPromises();expect(returnTimelineNode).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();expect(saveDevelopmentRound).not.toHaveBeenCalled();expect(router.currentRoute.value.query.step).toBe('sample-confirm');expect(wrapper.get('.review-history').text()).toContain('样品确认未通过');
 });
 it('protects unsaved owner confirmation input before asking for a return reason',async()=>{
  const d=failedConfirmation();d.workflow.reviewRecords=[];await launch(d);const panel=wrapper.getComponent(DevelopmentReviewPanel);await button('新增评审',panel).trigger('click');await flushPromises();await panel.get('textarea').setValue('尚未保存的负责人确认材料');const leave=vi.spyOn(ElMessageBox,'confirm').mockRejectedValueOnce('cancel');const reason=vi.spyOn(ElMessageBox,'prompt').mockResolvedValue({value:'不能误用这个原因'} as any);
  await button('返回画图').trigger('click');await flushPromises();expect(leave).toHaveBeenCalledWith('评审输入尚未保存，离开页面？','未保存输入');expect(reason).not.toHaveBeenCalled();expect(returnTimelineNode).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();expect((panel.get('textarea').element as HTMLTextAreaElement).value).toBe('尚未保存的负责人确认材料');
 });
 it('does not show return choices in read-only confirmation history',async()=>{
  const d=failedConfirmation();d.currentStepNo=5;await launch(d,4);expect(wrapper.findAll('button').some(b=>['返回画图','重新3D打样','退回上一阶段'].includes(b.text()))).toBe(false);
 });
});

describe('engineering evidence is produced at the right step',()=>{
 it('selects and removes representative phones in drawing and saves stable IDs with the adopted file version',async()=>{
  const phone=(id:number)=>({phoneModelId:id,brandId:1,brandName:'Apple',modelName:'iPhone '+id,status:'enabled',brandStatus:'enabled',codeLinkStatus:'unassigned'}) as CompatiblePhone;
  vi.mocked(getPhoneModelOptions).mockResolvedValue([phone(1)]);vi.mocked(getPhoneModels).mockResolvedValue({content:[phone(2)]} as any);
  await launch(current(2));const picker=wrapper.getComponent(SharedCompatiblePhoneSelector);await picker.get('.phone-model-trigger').trigger('click');await flushPromises();
  expect(getPhoneModels).toHaveBeenCalledWith(expect.objectContaining({keyword:'',selectionSearch:true}));picker.findAllComponents(ElCheckbox).find(v=>v.text().includes('iPhone 2'))!.vm.$emit('change',true);await flushPromises();
  await new DOMWrapper(document.body).get('.phone-model-panel .selected-tags .el-tag__close').trigger('click');await flushPromises();
  await new DOMWrapper(document.body).findAll('.phone-model-panel button').find(v=>v.text()==='完成选择（1）')!.trigger('click');await flushPromises();
  await wrapper.get('.material-row input[type=checkbox]').setValue(true);await button('保存草稿').trigger('click');await flushPromises();
  expect(researchAction).toHaveBeenCalledWith(42,'plan_draft',2,expect.objectContaining({phoneModelIds:[2],references:[{type:'file',id:11}]}));
  expect(researchAction).toHaveBeenCalledWith(42,'launch_draft',2,expect.objectContaining({stage:2,phoneModelIds:[2],references:[{type:'file',id:11}]}));
 });
 it('clears actual dates and results for another prototype while keeping the source maker and drawing',async()=>{
  const d=current(3);d.rounds=[{...record().rounds[0],roundId:11,details:{samplePurpose:'prototype_3d',makerName:'实际打样供应商',phoneModelIds:[1],designSummary:'D1',actualResult:'旧结果'}}];
  await launch(d);await button('新增一轮').trigger('click');await flushPromises();expect(wrapper.findComponent(ElDatePicker).props('modelValue')).toBe('');
  expect(wrapper.find('.sample-form .el-radio-group').exists()).toBe(false);await button('保存草稿').trigger('click');await flushPromises();
  expect(saveDevelopmentRound).toHaveBeenCalledWith(42,null,expect.objectContaining({recordDate:null,result:'pending',confirm:false,details:expect.objectContaining({samplePurpose:'prototype_3d',makerName:'实际打样供应商',previousRoundId:11,actualResult:''})}));
 });
 it('registers received prototypes as pending confirmation, never as a passing sample',async()=>{
  await launch(current(3));await button('登记实际收样').trigger('click');await flushPromises();
  expect(saveDevelopmentRound).toHaveBeenCalledWith(42,null,expect.objectContaining({confirm:true,result:'pending',recordDate:null,details:expect.objectContaining({samplePurpose:'prototype_3d'})}));expect(confirmTimelineNode).not.toHaveBeenCalled();
 });
 it('keeps prototype and mold-trial rounds separate and never offers prototype rounds as trial samples',async()=>{
  const d=current(6);d.rounds=[{...record().rounds[0],roundId:11,title:'开模前3D样',details:{samplePurpose:'prototype_3d',phoneModelIds:[1]}},{...record().rounds[0],roundId:12,title:'开模后试模样',details:{samplePurpose:'mold_trial',phoneModelIds:[1]}}];
  await launch(d);await wrapper.get('.toolbar .el-radio-button input[value="sample"]').setValue();await flushPromises();expect(wrapper.get('.sample-form').text()).not.toContain('开模前3D样');
  const roundSelect=wrapper.findAllComponents(ElSelect).find(s=>s.props('placeholder')==='选择样品轮次')!;
  expect(roundSelect.findAllComponents(ElOption).map(o=>o.props('value'))).toEqual([12]);await button('新增一轮').trigger('click');await flushPromises();await button('保存草稿').trigger('click');await flushPromises();
  expect(saveDevelopmentRound).toHaveBeenCalledWith(42,null,expect.objectContaining({details:expect.objectContaining({samplePurpose:'mold_trial',previousRoundId:12})}));
 });
 it('keeps a received prototype and its adopted versions read-only while leaving previews and a new round visible',async()=>{
  const d=current(3);d.rounds=[{...record().rounds[0],roundId:81,status:'confirmed',result:'pending',details:{samplePurpose:'prototype_3d',phoneModelIds:[1]}}];await launch(d);const materials=wrapper.getComponent(ResearchMaterialsPanel);
  expect(materials.props('selectable')).toBe(false);expect(wrapper.findAll('.material-row input[type=checkbox]').every(input=>(input.element as HTMLInputElement).disabled)).toBe(true);expect(materials.text()).toContain('预览／详情');expect(materials.text()).toContain('下载');expect(button('新增一轮').exists()).toBe(true);expect(wrapper.findAll('button').some(b=>b.text()==='保存草稿')).toBe(false);expect(wrapper.findAllComponents(ElOption).some(o=>String(o.props('label')).includes('已收样，待负责人确认'))).toBe(true);
  await projectAction('定位资料（1）');expect(saveDevelopmentRound).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();
 });
 it('uses only the confirmation editor to choose and save adopted versions, preserving the received prototype',async()=>{
  const d=current(4);d.workflow.prototypeRoundId=81;d.rounds=[{...record().rounds[0],roundId:81,status:'confirmed',result:'pending',details:{samplePurpose:'prototype_3d',phoneModelIds:[1]}}];d.materials.push(d.rounds[0].references[0]);
  d.workflow.reviewRecords=[{id:91,gate:'design',status:'draft',sampleRoundId:81,result:'pending',title:'已存样品确认草稿',references:d.rounds[0].references,checklist:{structure:true}}];vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as any);await launch(d);
  const panel=wrapper.getComponent(DevelopmentReviewPanel),materials=wrapper.getComponent(ResearchMaterialsPanel);expect(materials.props('selectable')).toBe(false);expect(materials.props('selected')).toEqual(['file:10']);expect(wrapper.findAll('.material-row input[type=checkbox]').every(input=>(input.element as HTMLInputElement).disabled)).toBe(true);expect(wrapper.findAll('button').some(b=>b.text()==='保存草稿')).toBe(false);
  await button('编辑',panel).trigger('click');await flushPromises();expect(panel.find('[aria-label="评审采用版本"]').exists()).toBe(true);expect(button('确认本次评审',panel).exists()).toBe(true);await button('保存评审草稿',panel).trigger('click');await flushPromises();expect(researchAction).toHaveBeenCalledWith(42,'review_save',2,expect.objectContaining({id:91,sampleRoundId:81,confirm:false,references:[{type:'file',id:10}]}));
  await button('删除草稿',panel).trigger('click');await flushPromises();expect(researchAction).toHaveBeenCalledWith(42,'review_delete',2,{id:91});expect(saveDevelopmentRound).not.toHaveBeenCalled();expect(saveResearchDesignDraft).not.toHaveBeenCalled();
 });
 it('uses the existing owner-confirmation panel after prototype receipt, with no second sample form',async()=>{
  await launch(current(4));expect(wrapper.getComponent(DevelopmentReviewPanel).props('stage')).toBe(4);expect(wrapper.get('.review-nav').text()).toBe('样品确认');expect(wrapper.find('.sample-form').exists()).toBe(false);expect(wrapper.findComponent(EngineeringBasisPanel).exists()).toBe(false);
 });
 it.each([1,2,3,4,5,6,7,8])('opens the LEMOS editor directly at research stage %s without writing an application',async(step)=>{
  const source=researchContext();vi.mocked(getResearchMoldApplicationContext).mockResolvedValue(source as any);await launch(current(step));
  expect(button('发起LEMOS开模申请').attributes('disabled')).toBeUndefined();await button('发起LEMOS开模申请').trigger('click');await flushPromises();
  expect(getResearchMoldApplicationContext).toHaveBeenCalledWith({projectId:42,version:2});expect(moldEditorOpenResearch).toHaveBeenCalledWith(source,{projectInputUnsaved:false});expect(startResearchMoldApplication).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();expect(saveResearchDesignDraft).not.toHaveBeenCalled();expect(wrapper.findAll('button').some(b=>b.text()==='准备申请草稿')).toBe(false);
 });
 it('opens the current project application while viewing an earlier read-only stage without saving that stage',async()=>{
  const router=await launch(current(6),1);expect(wrapper.findComponent(ElForm).props('disabled')).toBe(true);await button('发起LEMOS开模申请').trigger('click');await flushPromises();expect(moldEditorOpenResearch).toHaveBeenCalledOnce();expect(router.currentRoute.value.query.step).toBe('initiation');expect(researchAction).not.toHaveBeenCalled();expect(saveResearchDesignDraft).not.toHaveBeenCalled();
 });
 it('continues an existing draft without replacing its manual form or adopting it as mold design',async()=>{
  const d=current(5);d.applications=[{applicationId:88,status:'draft',canEditDraft:true,phoneModelId:1},{applicationId:89,status:'draft',canEditDraft:true,phoneModelId:null}];vi.mocked(getMoldApplication).mockResolvedValue({applicationId:88,productLineId:42,status:'draft',canEditDraft:true,form:{remark:'原申请人工填写'}} as any);await launch(d);
  const summaries=wrapper.get('[aria-label="LEMOS开模申请"]').findAll('.research-application-row');expect(summaries[0]!.text()).toContain('申请 #88 · 标准手机资料待读取');expect(summaries[1]!.text()).toContain('申请 #89 · 标准手机待补充');expect(wrapper.get('[aria-label="LEMOS开模申请"]').text()).toContain('草稿');expect(summaries[1]!.text()).not.toContain('#null');
  await button('继续填写',wrapper.get('[aria-label="LEMOS开模申请"]')).trigger('click');await flushPromises();
  expect(moldEditorOpen).toHaveBeenCalledWith(expect.objectContaining({applicationId:88,form:{remark:'原申请人工填写'}}));expect(startResearchMoldApplication).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();
 });
 it('keeps unsaved project input while directly opening the application editor with saved source data',async()=>{
  await launch(current(2));const notes=wrapper.get('textarea[placeholder="说明本次产品图的结构、功能及版本改动"]');await notes.setValue('此处未保存的新品要求');
  await button('发起LEMOS开模申请').trigger('click');await flushPromises();expect(moldEditorOpenResearch).toHaveBeenCalledWith(expect.any(Object),{projectInputUnsaved:true});expect((notes.element as HTMLTextAreaElement).value).toBe('此处未保存的新品要求');expect(saveResearchDesignDraft).not.toHaveBeenCalled();expect(startResearchMoldApplication).not.toHaveBeenCalled();
 });
 it('does not guess a standard phone from an unconfirmed plan or its free-text model',async()=>{
  const d=current(1);d.model='Honor 600 Lite';d.workflow.profileDraft.phoneModelIds=[];d.workflow.planDraft.phoneModelIds=[1];d.moldApplicationEntry.phoneModelIds=[];const source=researchContext();source.form.model='Honor 600 Lite';vi.mocked(getResearchMoldApplicationContext).mockResolvedValue(source as any);await launch(d);
  await button('发起LEMOS开模申请').trigger('click');await flushPromises();expect(getResearchMoldApplicationContext).toHaveBeenCalledWith({projectId:42,version:2});expect(moldEditorOpenResearch).toHaveBeenCalledWith(expect.objectContaining({phoneModelId:null,form:expect.objectContaining({model:'Honor 600 Lite'})}),expect.any(Object));expect(startResearchMoldApplication).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();
 });
 it('prioritizes the original linked approval on the research header and shows its actual phone name without code guesses',async()=>{
  const d=current(2);d.applications=[{applicationId:88,approvalNo:'original-approval',status:'submitted',businessStatus:'awaiting_approval',canEditDraft:false,phoneModelId:1333,model:'Honor 600 Lite 5G'}];vi.mocked(getMoldApplication).mockResolvedValue({...d.applications[0],productLineId:42,form:{model:'Honor 600 Lite 5G'},files:[],activities:[]} as any);const router=await launch(d);
  const header=wrapper.get('.project-header');expect(header.text()).toContain('查看LEMOS审批进度');expect(header.text()).not.toContain('发起LEMOS开模申请');const entry=wrapper.get('[aria-label="LEMOS开模申请"]');expect(entry.text()).toContain('original-approval · Honor 600 Lite 5G · 待 Tiffany 审核');expect(entry.text()).not.toContain('#1333');await button('查看LEMOS审批进度',header).trigger('click');await flushPromises();expect(router.currentRoute.value.path).toBe('/product-research');expect(router.currentRoute.value.query.step).toBe('drawing');expect(wrapper.getComponent(MoldApplicationDetailDrawer).text()).toContain('Honor 600 Lite 5G');expect(getResearchMoldApplicationContext).not.toHaveBeenCalled();expect(startResearchMoldApplication).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();
 });
 it('prioritizes continuing the original editable research draft instead of offering a new application',async()=>{
  const d=current(2);d.applications=[{applicationId:88,status:'draft',canEditDraft:true,model:'待确认机型'}];vi.mocked(getMoldApplication).mockResolvedValue({applicationId:88,productLineId:42,status:'draft',canEditDraft:true,form:{remark:'工程原草稿'}} as any);await launch(d);await button('继续LEMOS草稿',wrapper.get('.project-header')).trigger('click');await flushPromises();expect(moldEditorOpen).toHaveBeenCalledWith(expect.objectContaining({applicationId:88,form:{remark:'工程原草稿'}}));expect(getResearchMoldApplicationContext).not.toHaveBeenCalled();expect(startResearchMoldApplication).not.toHaveBeenCalled();
 });
 it('shows six original OA attachments in the existing materials area without adopting or copying them',async()=>{
  const d=current(2),files=Array.from({length:6},(_,i)=>({attachmentId:612+i,category:'attachment',fileName:`原审批结构图${i+1}.png`,fileSize:8,contentType:'image/png',downloadStatus:'downloaded'}));d.applications=[{applicationId:92381,approvalNo:'202610281759000301110',status:'submitted',businessStatus:'awaiting_approval',canArchiveFiles:false,files}];
  vi.mocked(downloadMoldFile).mockResolvedValue(new Blob(['image'],{type:'image/png'}));vi.spyOn(URL,'createObjectURL').mockReturnValue('blob:original-oa');vi.spyOn(URL,'revokeObjectURL').mockImplementation(()=>{});await launch(d);
  const panel=wrapper.getComponent(ResearchMaterialsPanel),group=panel.get('[aria-label="原LEMOS审批附件 202610281759000301110"]');expect(group.text()).toContain('6 份');expect(group.findAllComponents(MoldApplicationFile)).toHaveLength(6);expect(group.findAll('.file-picture')).toHaveLength(6);expect(group.findAll('input[type=checkbox]')).toHaveLength(0);expect(group.text()).not.toContain('上传新版');expect(group.text()).not.toContain('删除草稿');expect(group.text()).not.toContain('归档原审批附件');expect(panel.props('materials')).toEqual(d.materials);expect(panel.props('selected')).not.toContain('file:612');
  expect(group.findAllComponents(MoldApplicationFile).map((c:VueWrapper<any>)=>[c.props('applicationId'),c.props('file').attachmentId])).toEqual(files.map(file=>[92381,file.attachmentId]));expect(startResearchMoldApplication).not.toHaveBeenCalled();expect(uploadProjectAttachment).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();
 });
 it('does not present unsubmitted PLM draft files as original OA attachments',async()=>{
  const d=current(2);d.applications=[null,'','  '].map((approvalNo,i)=>({applicationId:88+i,approvalNo,status:'draft',canEditDraft:true,canArchiveFiles:false,files:[{attachmentId:300+i,fileName:'本地草稿图.png',contentType:'image/png',downloadStatus:'downloaded'}]}));await launch(d);expect(wrapper.find('.original-application-files').exists()).toBe(false);expect(wrapper.findComponent(MoldApplicationFile).exists()).toBe(false);expect(downloadMoldFile).not.toHaveBeenCalled();expect(archiveMoldFiles).not.toHaveBeenCalled();
 });
 it('archives only original application files without reloading project data or changing unsaved input and adopted references',async()=>{
  const d=current(2),pending={attachmentId:612,category:'attachment',fileName:'原审批结构图.png',fileSize:8,contentType:'application/octet-stream',downloadStatus:'download_failed',archiveError:'下载超时，请重试'};d.applications=[{applicationId:92381,approvalNo:'202610281759000301110',status:'submitted',businessStatus:'awaiting_approval',canArchiveFiles:true,files:[pending]}];await launch(d);
  const notes=wrapper.get('textarea[placeholder="说明本次产品图的结构、功能及版本改动"]');await notes.setValue('未保存的产品结构要求');await wrapper.get('.material-row input[type=checkbox]').setValue(true);const selected=[...wrapper.getComponent(ResearchMaterialsPanel).props('selected')];expect(selected).toContain('file:11');const archived={...pending,contentType:'application/pdf',downloadStatus:'downloaded',archiveError:null};vi.mocked(archiveMoldFiles).mockResolvedValue({applicationId:92381,files:[archived],canArchiveFiles:false,status:'completed',approvalNo:'must-not-replace-original-status'} as any);
  await button('重试归档原附件').trigger('click');await flushPromises();expect(archiveMoldFiles).toHaveBeenCalledExactlyOnceWith(92381);expect(getProductResearchDetail).toHaveBeenCalledOnce();expect(wrapper.getComponent(MoldApplicationFile).props('file').attachmentId).toBe(612);expect(wrapper.getComponent(MoldApplicationFile).text()).toContain('已归档');expect(wrapper.find('[aria-label="原LEMOS审批附件 202610281759000301110"]').exists()).toBe(true);expect((notes.element as HTMLTextAreaElement).value).toBe('未保存的产品结构要求');expect(wrapper.getComponent(ResearchMaterialsPanel).props('selected')).toEqual(selected);expect(useCollaborationWorkspace().revision).toBe(1);expect(saveResearchDesignDraft).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();expect(startResearchMoldApplication).not.toHaveBeenCalled();expect(uploadProjectAttachment).not.toHaveBeenCalled();
 });
 it('keeps an archive permission failure visible and does not infer OA access from project materials',async()=>{
  const d=current(2);d.applications=[{applicationId:92381,approvalNo:'original-oa',canArchiveFiles:true,files:[{attachmentId:612,fileName:'原审批图.png',downloadStatus:'pending_authorization'}]}];await launch(d);vi.mocked(archiveMoldFiles).mockRejectedValue(new Error('当前账号无原审批附件归档权限'));await button('归档原审批附件').trigger('click');await flushPromises();expect(wrapper.text()).toContain('当前账号无原审批附件归档权限');expect(useCollaborationWorkspace().revision).toBe(0);expect(saveResearchDesignDraft).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();
  wrapper.unmount();await launch(current(2));expect(wrapper.findComponent(MoldApplicationFile).exists()).toBe(false);expect(wrapper.find('.original-application-files').exists()).toBe(false);expect(downloadMoldFile).not.toHaveBeenCalled();
 });
 it('opens unique original application progress without creating or opening another draft',async()=>{
  vi.mocked(getResearchMoldApplicationContext).mockResolvedValue({...researchContext(),existingApplication:{applicationId:88,status:'submitted',canEditDraft:false}} as any);vi.mocked(getMoldApplication).mockResolvedValue({applicationId:88,productLineId:42,status:'submitted',canEditDraft:false,form:{},files:[],activities:[]} as any);const router=await launch(current(2));await button('发起LEMOS开模申请').trigger('click');await flushPromises();
  expect(router.currentRoute.value.path).toBe('/product-research');expect(router.currentRoute.value.query.productId).toBe('42');expect(wrapper.getComponent(MoldApplicationDetailDrawer).text()).toContain('申请 #88');expect(moldEditorOpen).not.toHaveBeenCalled();expect(moldEditorOpenResearch).not.toHaveBeenCalled();expect(startResearchMoldApplication).not.toHaveBeenCalled();
 });
 it('rechecks full-detail permission before continuing a unique original draft',async()=>{
  vi.mocked(getResearchMoldApplicationContext).mockResolvedValue({...researchContext(),existingApplication:{applicationId:88,status:'draft',canEditDraft:true}} as any);vi.mocked(getMoldApplication).mockResolvedValue({applicationId:88,productLineId:42,status:'draft',canEditDraft:false,form:{},files:[],activities:[]} as any);const router=await launch(current(2));await button('发起LEMOS开模申请').trigger('click');await flushPromises();expect(getMoldApplication).toHaveBeenCalledWith(88);expect(moldEditorOpen).not.toHaveBeenCalled();expect(moldEditorOpenResearch).not.toHaveBeenCalled();expect(router.currentRoute.value.path).toBe('/product-research');expect(wrapper.getComponent(MoldApplicationDetailDrawer).text()).toContain('申请 #88');
 });
 it('ignores full application detail arriving after a login change',async()=>{
  let finish:((row:any)=>void)|undefined;vi.mocked(getResearchMoldApplicationContext).mockResolvedValue({...researchContext(),existingApplication:{applicationId:88}} as any);vi.mocked(getMoldApplication).mockImplementation(()=>new Promise(resolve=>{finish=resolve;}));await launch(current(2));await button('发起LEMOS开模申请').trigger('click');await flushPromises();expect(finish).toBeDefined();useUserStore().token='replacement-login';finish!({applicationId:88,status:'draft',canEditDraft:true,files:[{attachmentId:201,fileName:'其他身份图片.png'}]});await flushPromises();expect(moldEditorOpen).not.toHaveBeenCalled();expect(moldEditorOpenResearch).not.toHaveBeenCalled();
 });
 it('keeps another applicant draft read-only and routes to progress',async()=>{
  const d=current(2);d.applications=[{applicationId:88,status:'draft',canEditDraft:false,phoneModelId:1}];const router=await launch(d);const entry=wrapper.get('[aria-label="LEMOS开模申请"]');expect(entry.findAll('button').map(b=>b.text())).toEqual(['选择其他型号','查看审批进度']);await button('查看审批进度',entry).trigger('click');await flushPromises();expect(router.currentRoute.value.path).toBe('/product-research');expect(wrapper.getComponent(MoldApplicationDetailDrawer).text()).toContain('申请 #88');expect(moldEditorOpen).not.toHaveBeenCalled();expect(startResearchMoldApplication).not.toHaveBeenCalled();
 });
 it('ignores a readonly source response after login changes and blocks repeat clicks while loading',async()=>{
  let finish:((row:any)=>void)|undefined;vi.mocked(getResearchMoldApplicationContext).mockImplementation(()=>new Promise(resolve=>{finish=resolve;}));await launch(current(1));await button('发起LEMOS开模申请').trigger('click');await button('发起LEMOS开模申请').trigger('click');expect(getResearchMoldApplicationContext).toHaveBeenCalledOnce();useUserStore().token='new-login';finish!(researchContext());await flushPromises();expect(moldEditorOpenResearch).not.toHaveBeenCalled();expect(startResearchMoldApplication).not.toHaveBeenCalled();
 });
 it.each([{lockStatus:'frozen'},{lockStatus:'abandoned'},{status:'archived'},{status:'released'},{canWrite:false}])('retains the original stopped and permission protection %s',async(patch)=>{
  await launch({...current(1),...patch});expect(button('发起LEMOS开模申请').attributes('disabled')).toBeDefined();expect(getResearchMoldApplicationContext).not.toHaveBeenCalled();expect(startResearchMoldApplication).not.toHaveBeenCalled();
 });
 it('opens a manufacturing node without inventing dates and resumes it with the current concurrency version',async()=>{
  const d=current(6);d.rounds=[{...record().rounds[0],details:{samplePurpose:'mold_trial'}}];d.molds=[{moldSetId:7,assetNumber:'QA-7',development:{node:'making',version:3,draft:{node:'making',nodeVersion:2,actualDate:'2026-10-15',result:'complete'}}}];
  await launch(d);await wrapper.get('.toolbar .el-radio-button input[value="mold"]').setValue();await flushPromises();
  await button('办理当前节点').trigger('click');await flushPromises();await button('确认本节点').trigger('click');await flushPromises();
  expect(researchAction).toHaveBeenCalledWith(42,'mold_node',2,expect.objectContaining({moldSetId:7,nodeVersion:3,confirm:true,actualDate:'2026-10-15'}));
 });
 it('exposes final BOM, routing and engineering confirmation in trial, without showing them in initiation',async()=>{
  await launch(current(7));expect(wrapper.findComponent(EngineeringBasisPanel).exists()).toBe(true);expect(wrapper.findComponent(ResearchMaterialsPanel).props('hideEngineering')).toBe(false);expect(wrapper.findAll('.el-tabs__item').map(t=>t.text())).toEqual(['试产资料','验证与结果']);
  await tab('验证与结果');expect(wrapper.get('.review-panel').text()).toContain('试产计划数量');expect(wrapper.get('.review-panel').text()).toContain('最低良率');
 });
 it('shares one final BOM and process selection between the trial editor and inline materials',async()=>{
  const d=current(7);d.workflow.releaseDraft={references:[{type:'file',id:11},{type:'bom',id:31},{type:'process',id:32}]};d.materials=[...d.materials,{type:'bom',id:31,name:'旧用料',versionNo:'B1',status:'draft'},{type:'bom',id:33,name:'新用料',versionNo:'B2',status:'active'},{type:'process',id:32,name:'装配工艺',versionNo:'P1',status:'active'}];await launch(d);
  const panel=wrapper.getComponent(EngineeringBasisPanel),materials=wrapper.getComponent(ResearchMaterialsPanel);materials.vm.$emit('update:selected',['file:11','bom:31','process:32','bom:33']);await flushPromises();expect(panel.props('selected')).toEqual(['file:11','process:32','bom:33']);
  panel.vm.$emit('update:selected',['file:11','process:32','bom:31']);await flushPromises();expect(materials.props('selected')).toEqual(['file:11','process:32','bom:31']);await button('保存草稿').trigger('click');await flushPromises();
  expect(researchAction).toHaveBeenCalledWith(42,'release_draft',2,expect.objectContaining({references:[{type:'file',id:11},{type:'process',id:32},{type:'bom',id:31}]}));
 });
 it('requires a reason when changing the final engineering plan without making the engineer return to initiation',async()=>{
  const d=current(7);d.canConfirmEngineering=true;d.engineering={confirmed:true,current:false,basis:{supplierAssignments:[],references:[]},checks:[missing('engineering-bom','正式BOM')]};await launch(d);const panel=wrapper.getComponent(EngineeringBasisPanel);
  expect(panel.text()).not.toContain('返回立项');await button('保存并确认工程方案',panel).trigger('click');await flushPromises();expect(researchAction).not.toHaveBeenCalled();expect(wrapper.text()).toContain('请填写工程方案变更原因');
 });
 it('shows shipment completion and prevents repeating an already confirmed eighth-step handover',async()=>{
  vi.mocked(getProjectTimeline).mockResolvedValue({projectId:42,currentStepNo:8,currentConfirmed:true,nodes:PRODUCT_RESEARCH_STAGES.map(s=>({stepNo:s.step,nodeKey:`RESEARCH_${s.key}`,nodeName:s.name}))} as any);vi.mocked(checkResearchStage).mockResolvedValue({version:2,ready:true,checks:[{...missing('shipment','本批发模'),complete:true}]});await launch(current(8));
  const done=button('发模已完成');expect(done.attributes('disabled')).toBeDefined();await done.trigger('click');await flushPromises();expect(confirmTimelineNode).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();expect(wrapper.findAll('button').some(b=>b.text()==='完成本项目发模')).toBe(false);
 });
 it('keeps an eighth-step revision actionable despite the original timeline already being confirmed',async()=>{
  const d=current(8);d.status='released';d.workflow.revision={revisionNo:2,stage:8};vi.mocked(getProjectTimeline).mockResolvedValue({projectId:42,currentStepNo:8,currentConfirmed:true,nodes:PRODUCT_RESEARCH_STAGES.map(s=>({stepNo:s.step,nodeKey:`RESEARCH_${s.key}`,nodeName:s.name}))} as any);vi.mocked(checkResearchStage).mockResolvedValue({version:2,ready:true,checks:[{...missing('shipment','本批发模'),complete:true}]});vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as any);await launch(d);
  const complete=button('完成本项目发模');expect(complete.attributes('disabled')).toBeUndefined();await complete.trigger('click');await flushPromises();expect(researchAction).toHaveBeenCalledWith(42,'revision_handover',2,{stage:8});expect(confirmTimelineNode).not.toHaveBeenCalled();
 });
 it('starts a shipment with no adopted technical references and saves only explicitly selected logistics files',async()=>{
  const d=current(8);d.materials.push({type:'bom',id:31,name:'最终BOM',versionNo:'B1',status:'active'},{type:'process',id:32,name:'最终工艺',versionNo:'P1',status:'active'},{type:'file',id:21,name:'首批物流单.pdf',versionNo:'V1',status:'draft',category:'logistics'});d.workflow.reviewRecords=[{gate:'design',status:'confirmed',result:'passed',references:d.materials.slice(0,3)}];d.workflow.releaseDraft={references:d.materials.slice(0,3)};await launch(d);
  const materials=wrapper.getComponent(ResearchMaterialsPanel);expect(materials.props('selected')).toEqual([]);await button('保存草稿').trigger('click');await flushPromises();expect(researchAction).toHaveBeenLastCalledWith(42,'launch_draft',2,expect.objectContaining({stage:8,references:[]}));
  materials.vm.$emit('update:selected',['file:21']);await flushPromises();await button('保存草稿').trigger('click');await flushPromises();expect(researchAction).toHaveBeenLastCalledWith(42,'launch_draft',2,expect.objectContaining({stage:8,references:[{type:'file',id:21}]}));
 });
 it('confirms only the selected shipment batch and keeps unshipped sets and the project open',async()=>{
  const d=current(8);d.molds=[{moldSetId:7,assetNumber:'MBA200007',actualShipDate:'2026-10-23'},{moldSetId:8,assetNumber:'MBA200008',actualShipDate:null}];await launch(d);
  fields('本批模具套').getComponent(ElSelect).vm.$emit('update:modelValue',[7]);await fields('墨西哥收货地点／接收方').get('input').setValue('墨西哥一厂');await button('确认本批发模').trigger('click');await flushPromises();
  expect(researchAction).toHaveBeenCalledWith(42,'launch_draft',2,expect.objectContaining({stage:8,moldSetIds:[7],recipient:'墨西哥一厂'}));expect(researchAction).toHaveBeenCalledWith(42,'shipment_confirm',2,{});expect(confirmTimelineNode).not.toHaveBeenCalled();expect(wrapper.text()).toContain('MBA200008');expect(wrapper.text()).toContain('尚未发模');
 });
 it('keeps shipping dates in the existing schedule and saves only recipient and logistics notes here',async()=>{
  const d=current(8);d.molds=[{moldSetId:7,assetNumber:'MBA200007',actualShipDate:'2026-10-23'}];await launch(d);
  expect(wrapper.text()).toContain('2026-10-23');expect(wrapper.findComponent(ElDatePicker).exists()).toBe(false);await fields('墨西哥收货地点／接收方').get('input').setValue('墨西哥一厂');await fields('物流说明').get('textarea').setValue('首批发运');await button('保存草稿').trigger('click');await flushPromises();
  expect(researchAction).toHaveBeenCalledWith(42,'launch_draft',2,expect.objectContaining({stage:8,recipient:'墨西哥一厂',notes:'首批发运'}));
 });
});

describe('late-stage gaps take engineers to an editable existing entry',()=>{
 it.each([[5,4],[7,6]])('returns a missing design confirmation from step %s to step %s through the original reason flow',async(step,target)=>{
  const d=current(step);vi.mocked(checkResearchStage).mockResolvedValue({version:2,ready:false,checks:[missing('review:design','已确认样品方案')]});vi.mocked(getProjectTimeline).mockResolvedValue({projectId:42,currentStepCode:`RESEARCH_${PRODUCT_RESEARCH_STAGES[step-1]!.key}`,currentStepNo:step,nodes:PRODUCT_RESEARCH_STAGES.map(s=>({stepNo:s.step,nodeKey:`RESEARCH_${s.key}`,nodeName:s.name}))} as any);vi.spyOn(ElMessageBox,'prompt').mockResolvedValue({value:'方案变化，需要补充确认'} as any);
  const router=await launch(d);await wrapper.getComponent(StageReadinessChecklist).vm.checkNow();await flushPromises();await wrapper.get('button[aria-label="补齐已确认样品方案"]').trigger('click');await flushPromises();expect(returnTimelineNode).toHaveBeenCalledWith(42,`RESEARCH_${PRODUCT_RESEARCH_STAGES[step-1]!.key}`,'方案变化，需要补充确认',true,undefined,2,target);expect(router.currentRoute.value.query.step).toBe(PRODUCT_RESEARCH_STAGES[target-1]!.key);expect(saveDevelopmentRound).not.toHaveBeenCalled();
 });
 it.each([null,'2026-10-23'])('locates a shipment gap with actual date %s without sending a confirmed-date batch back around the schedule',async actualShipDate=>{
  const d=current(8);d.molds=[{moldSetId:7,assetNumber:'MBA200007',actualShipDate}];vi.mocked(checkResearchStage).mockResolvedValue({version:2,ready:false,checks:[missing('shipment:7','本套发模依据')]});const router=await launch(d);const scroll=vi.fn();Object.defineProperty(wrapper.get('[data-readiness-target="shipment"]').element,'scrollIntoView',{value:scroll,configurable:true});await wrapper.getComponent(StageReadinessChecklist).vm.checkNow();await flushPromises();await wrapper.get('button[aria-label="补齐本套发模依据"]').trigger('click');await flushPromises();
  if(actualShipDate){expect(router.currentRoute.value.path).toBe('/product-research');expect(router.currentRoute.value.query.step).toBe('ship');expect(scroll).toHaveBeenCalledWith({block:'center'});expect(button('确认本批发模').exists()).toBe(true);}
  else {expect(router.currentRoute.value.path).toBe('/mold-schedules');expect(router.currentRoute.value.query).toEqual(expect.objectContaining({projectId:'42',moldSetId:'7'}));}
  expect(researchAction).not.toHaveBeenCalled();expect(confirmTimelineNode).not.toHaveBeenCalled();
 });
});

describe('draft protection, permissions and shared workspaces',()=>{
 it('opens earlier steps read-only while parallel promotion work remains available',async()=>{
  await launch(current(7),1);expect(wrapper.findComponent(ElForm).props('disabled')).toBe(true);expect(wrapper.findAll('button').some(b=>b.text()==='保存草稿')).toBe(false);expect(wrapper.get('.stage-materials').text()).not.toContain('从本地上传');
  await projectAction('推广');expect(button('新增推广').exists()).toBe(true);
 });
 it('returns from a business workspace to the actual current step using a stable step key',async()=>{
  vi.mocked(getProductResearchDetail).mockResolvedValue(current(7));const router=await start('/product-research-promotions?productId=42&step=initiation');await button('返回当前研发阶段').trigger('click');await flushPromises();expect(router.currentRoute.value.query).toEqual({step:'pilot',productId:'42'});
 });
 it('keeps unsaved text when a cross-module reference refreshes',async()=>{
  const d=current(1);await launch(d);await wrapper.get('[data-readiness-target="product-name"] input').setValue('未保存名称');vi.mocked(getProductResearchDetail).mockResolvedValue({...d,workflow:{...d.workflow,version:3},materials:[...d.materials,{type:'file',id:99,name:'新附件',versionNo:'V1',status:'draft'}]});useCollaborationWorkspace().changed();await flushPromises();expect((wrapper.get('[data-readiness-target="product-name"] input').element as HTMLInputElement).value).toBe('未保存名称');expect(saveResearchDesignDraft).not.toHaveBeenCalled();
 });
 it('deletes an unconfirmed project draft through its visible entry and returns to the unified list',async()=>{
  vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as any);vi.mocked(deleteProductResearchDraft).mockResolvedValue(undefined);const router=await launch();await projectAction('删除新品草稿');await flushPromises();
  expect(deleteProductResearchDraft).toHaveBeenCalledWith(42,2);expect(router.currentRoute.value.query).toEqual({step:'initiation'});expect(getProductResearch).toHaveBeenCalled();
 });
 it('keeps the entered project when the server refuses deletion because downstream records exist',async()=>{
  vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as any);vi.mocked(deleteProductResearchDraft).mockRejectedValueOnce(Error('已有下游记录，不能删除'));const router=await launch();await projectAction('删除新品草稿');await flushPromises();
  expect(router.currentRoute.value.query.productId).toBe('42');expect(wrapper.text()).toContain('已有下游记录');
 });
 it('deletes a prototype draft from the current round while confirmed samples require recorded voiding',async()=>{
  const d=current(3);d.rounds=[{...record().rounds[0],roundId:81,status:'draft',result:'pending',details:{samplePurpose:'prototype_3d'}}];vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as any);await launch(d);await button('删除样品草稿').trigger('click');await flushPromises();expect(deleteDevelopmentDraft).toHaveBeenCalledWith(42,81,2);
  wrapper.unmount();d.rounds[0].status='confirmed';await launch(d);expect(wrapper.findAll('button').some(b=>b.text()==='删除样品草稿')).toBe(false);expect(button('作废本轮样品').exists()).toBe(true);
 });
 it('deletes only attachment drafts through the original file API and keeps adopted records visible after a rejection',async()=>{
  vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as any);vi.mocked(deleteAttachment).mockRejectedValueOnce(Error('已被确认记录引用，不能删除'));await launch();await button('删除草稿',wrapper.get('.stage-materials')).trigger('click');await flushPromises();
  expect(deleteAttachment).toHaveBeenCalledWith(11);expect(wrapper.get('.stage-materials').text()).toContain('结构图.pdf');expect(wrapper.text()).toContain('已被确认记录引用');
 });
 it('uploads a replacement file version through the existing family API only after a change reason is filled',async()=>{
  await launch();await button('上传新版',wrapper.get('.stage-materials')).trigger('click');await flushPromises();const dialog=wrapper.get('.test-dialog[data-title="上传新版本"]');const file=new File(['new'],'结构图.pdf',{type:'application/pdf'});Object.assign(file,{uid:81});dialog.getComponent(ElUpload).vm.$emit('update:fileList',[{uid:81,name:file.name,raw:file}]);await flushPromises();
  await dialog.get('input[placeholder="如 D1、V2"]').setValue('D3');await button('保存版本',dialog).trigger('click');await flushPromises();expect(uploadAttachmentVersion).not.toHaveBeenCalled();expect(dialog.text()).toContain('请填写新版本变更原因');
  await dialog.get('textarea').setValue('修正按键孔位置');vi.mocked(uploadAttachmentVersion).mockResolvedValueOnce({attachmentId:12} as any);await button('保存版本',dialog).trigger('click');await flushPromises();expect(uploadAttachmentVersion).toHaveBeenCalledWith(11,file,'D3','修正按键孔位置');expect(uploadProjectAttachment).not.toHaveBeenCalled();
 });
 it('preserves restricted supplier relationships while saving other project requirements',async()=>{
  const d=current(1);d.workflow.planDraft={suppliersRestricted:true,supplierAssignments:[]};await launch(d);await wrapper.get('[data-readiness-target="requirements"] textarea').setValue('只修改可见需求');await button('保存草稿').trigger('click');await flushPromises();
  const payload=vi.mocked(saveResearchDesignDraft).mock.calls[0]![2];expect(payload).not.toHaveProperty('supplierAssignments');expect(payload).not.toHaveProperty('suppliersRestricted');
 });
 it('keeps monetary editing and estimates behind existing finance permission',async()=>{
  await launch();useUserStore().permissions=['project:view','documents:write'];await flushPromises();expect(wrapper.text()).not.toContain('目标单件成本');expect(wrapper.findAll('button').some(b=>b.text()==='单件成本估算')).toBe(false);expect(wrapper.findAll('button').some(b=>b.text()==='预计费用／预算')).toBe(false);
 });
 it('restores unsaved initiation input after viewing another step without saving a parallel draft',async()=>{
  const router=await launch();await wrapper.get('[data-readiness-target="product-name"] input').setValue('未保存的透甲设计要求');await router.push('/product-research?productId=42&step=drawing');await flushPromises();await router.push('/product-research?productId=42&step=initiation');await flushPromises();expect((wrapper.get('[data-readiness-target="product-name"] input').element as HTMLInputElement).value).toBe('未保存的透甲设计要求');expect(saveResearchDesignDraft).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();
 });
 it('locates current-step missing fields without creating any business records',async()=>{
  Element.prototype.scrollIntoView=vi.fn();vi.mocked(checkResearchDesign).mockResolvedValue({version:2,ready:false,checks:[missing('owner','项目负责人'),missing('requirements','开发需求')]});await launch();await wrapper.getComponent(StageReadinessChecklist).vm.checkNow();await flushPromises();await button('展开缺项（2）').trigger('click');
  for(const title of ['项目负责人','开发需求']){await wrapper.get(`button[aria-label="补齐${title}"]`).trigger('click');await flushPromises();}expect(saveResearchDesignDraft).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();expect(Element.prototype.scrollIntoView).toHaveBeenCalled();
 });
 it.each([1,2,3,4,5,6,7,8])('shows step %s images and attachments inline immediately',async step=>{
  await launch(current(step));expect(wrapper.findAll('.stage-materials')).toHaveLength(1);expect(wrapper.get('.stage-materials').text()).toContain('从本地上传');expect(wrapper.get('.stage-materials').text()).toContain('将图片或附件拖到这里');
  expect(wrapper.getComponent(ResearchMaterialsPanel).props('hideEngineering')).toBe(step<7);
 });
 it('does not let read-only uploads bypass the current-step entry',async()=>{
  const router=await launch(current(1),2);wrapper.getComponent(ResearchMaterialsPanel).vm.$emit('files',[new File(['x'],'设计图.png',{type:'image/png'})]);await flushPromises();expect(wrapper.find('.test-dialog').exists()).toBe(false);expect(uploadProjectAttachment).not.toHaveBeenCalled();
  await button('前往当前阶段').trigger('click');await flushPromises();expect(router.currentRoute.value.query.step).toBe('initiation');
 });
});

it('keeps typed engineering input and completed uploads while retrying only the failed part of a file batch',async()=>{
 const d=current(1);await launch(d);await wrapper.get('[data-readiness-target="product-name"] input').setValue('尚未保存的工程师输入');expect(wrapper.get('[aria-label="本阶段图片与附件"]').text()).toContain('从本地上传');
 const first=new File(['one'],'附件01.txt',{type:'text/plain'}),second=new File(['two'],'附件02.txt',{type:'text/plain'});
 const local=wrapper.get('input[type=file]');Object.defineProperty(local.element,'files',{value:[first,second],configurable:true});await local.trigger('change');await flushPromises();expect(wrapper.getComponent(ElUpload).props('fileList')!.map((f:any)=>f.raw)).toEqual([first,second]);
 vi.mocked(uploadProjectAttachment).mockResolvedValueOnce({attachmentId:81} as any).mockRejectedValueOnce(new Error('隔离网络失败')).mockResolvedValueOnce({attachmentId:82} as any);
 await button('保存版本').trigger('click');await flushPromises();
 expect(uploadProjectAttachment).toHaveBeenCalledTimes(2);expect(wrapper.get('.test-dialog [role="alert"]').text()).toContain('隔离网络失败');expect((wrapper.get('[data-readiness-target="product-name"] input').element as HTMLInputElement).value).toBe('尚未保存的工程师输入');
 await button('继续上传').trigger('click');await flushPromises();expect(uploadProjectAttachment).toHaveBeenCalledTimes(3);expect(vi.mocked(uploadProjectAttachment).mock.calls.map(c=>c[1].name)).toEqual(['附件01.txt','附件02.txt','附件02.txt']);expect((wrapper.get('[data-readiness-target="product-name"] input').element as HTMLInputElement).value).toBe('尚未保存的工程师输入');expect(saveResearchDesignDraft).not.toHaveBeenCalled();
});
it('rejects unsupported, oversized and excessive dropped batches before any upload, then reuses the original mixed upload queue',async()=>{
 await launch(current(1));const panel=wrapper.getComponent(ResearchMaterialsPanel);
 const large=new File(['x'],'过大.png',{type:'image/png'});Object.defineProperty(large,'size',{value:200*1024*1024+1});
 for(const batch of [[new File(['x'],'不支持.exe')],[large],Array.from({length:21},(_,i)=>new File(['x'],`附件${i}.txt`))]){
  panel.vm.$emit('files',batch);await flushPromises();expect(wrapper.find('.test-dialog').exists()).toBe(false);expect(uploadProjectAttachment).not.toHaveBeenCalled();
 }
 const image=new File(['image'],'设计图.png',{type:'image/png'}),document=new File(['plan'],'计划.docx');
 await panel.get('aside').trigger('drop',{dataTransfer:{types:['Files'],files:[image,document]}});await flushPromises();
 expect(wrapper.getComponent(ElUpload).props('fileList')!.map((f:any)=>f.raw)).toEqual([image,document]);expect(uploadProjectAttachment).not.toHaveBeenCalled();
 vi.mocked(uploadProjectAttachment).mockResolvedValueOnce({attachmentId:91} as any).mockResolvedValueOnce({attachmentId:92} as any);
 await button('保存版本').trigger('click');await flushPromises();expect(vi.mocked(uploadProjectAttachment).mock.calls.map(c=>c[1])).toEqual([image,document]);expect(saveResearchDesignDraft).not.toHaveBeenCalled();
});

describe('supplier selection and replacement reuse the existing transaction',()=>{
 const assignment=()=>({mode:'supplier',supplierCode:'SUP-A',role:'mold',scope:'本次开发',basis:'原报价'});
 const withSupplier=(step=1)=>{const d=current(step);d.canChangeSupplier=true;d.canConfirmEngineering=true;d.workflow.planDraft={...d.workflow.planDraft,supplierAssignments:[assignment()]};if(step>1)d.workflow.plan={...d.workflow.plan,supplierAssignments:[assignment()]};return d;};
 const choices=()=>vi.mocked(getResearchOwnerOptions).mockResolvedValue({owners:[{userId:1,name:'工程师'}],canSelectSupplier:true,suppliers:[{supplierId:1,supplierCode:'SUP-A',supplierName:'原技术供方',status:'active'},{supplierId:2,supplierCode:'SUP-B',supplierName:'新技术供方',status:'active'}]});
 const dialog=()=>wrapper.get('.test-dialog[data-title="更换供应商"]');
 async function chooseReplacement(){dialog().getComponent(ElSelect).vm.$emit('update:modelValue','SUP-B');await flushPromises();}
 it('selects an optional external mold supplier with scope and quotation evidence still unknown',async()=>{
  choices();await launch();const select=wrapper.get('[data-readiness-target="supplier"]').getComponent(ElSelect);select.vm.$emit('update:modelValue','SUP-A');select.vm.$emit('change','SUP-A');await flushPromises();await button('保存草稿').trigger('click');await flushPromises();
  expect(saveResearchDesignDraft).toHaveBeenCalledWith(42,expect.any(Object),expect.objectContaining({supplierAssignments:[expect.objectContaining({mode:'supplier',role:'mold',supplierCode:'SUP-A',basis:''})]}));expect(researchAction).not.toHaveBeenCalled();
 });
 it.each([1,2,3,4,5,6,7,8])('records a supplier change at step %s without rewriting past sample facts',async step=>{
  choices();const d=withSupplier(step);await launch(d);await button('更换供应商').trigger('click');await flushPromises();await chooseReplacement();await dialog().get('textarea[aria-label="供应商更换原因"]').setValue('原供方技术无法实现');
  const updated=structuredClone(d);updated.workflow.version=3;updated.workflow.planDraft.supplierAssignments=[{...assignment(),supplierCode:'SUP-B',basis:''}];if(step>1)updated.workflow.plan.supplierAssignments=updated.workflow.planDraft.supplierAssignments;vi.mocked(researchAction).mockResolvedValue(updated);
  await button('保存记录',dialog()).trigger('click');await flushPromises();expect(researchAction).toHaveBeenCalledWith(42,'supplier_change',2,{stage:step,previousSupplierCode:'SUP-A',supplierCode:'SUP-B',role:'mold',scope:'本次开发',basis:'',reason:'原供方技术无法实现'});expect(saveDevelopmentRound).not.toHaveBeenCalled();expect(saveResearchDesignDraft).not.toHaveBeenCalled();
 });
 it('keeps a missing reason or cancellation out of the server',async()=>{
  choices();await launch(withSupplier(5));await button('更换供应商').trigger('click');await flushPromises();await chooseReplacement();await button('保存记录',dialog()).trigger('click');await flushPromises();expect(dialog().text()).toContain('请填写供应商更换原因');expect(researchAction).not.toHaveBeenCalled();await button('取消',dialog()).trigger('click');await flushPromises();expect(wrapper.find('.test-dialog').exists()).toBe(false);
 });
 it('preserves typed initiation requirements when replacing its saved supplier',async()=>{
  choices();const d=withSupplier();await launch(d);await wrapper.get('[data-readiness-target="product-name"] input').setValue('保留这份未保存名称');await button('更换供应商').trigger('click');await flushPromises();await chooseReplacement();await dialog().get('textarea[aria-label="供应商更换原因"]').setValue('原供方无法制作');
  const updated=structuredClone(d);updated.workflow.version=3;updated.workflow.planDraft.supplierAssignments=[{...assignment(),supplierCode:'SUP-B',basis:''}];vi.mocked(researchAction).mockResolvedValue(updated);await button('保存记录',dialog()).trigger('click');await flushPromises();expect((wrapper.get('[data-readiness-target="product-name"] input').element as HTMLInputElement).value).toBe('保留这份未保存名称');
 });
 it('retains the replacement reason after a concurrency conflict',async()=>{
  choices();await launch(withSupplier(5));await button('更换供应商').trigger('click');await flushPromises();await chooseReplacement();await dialog().get('textarea[aria-label="供应商更换原因"]').setValue('原供方工艺不能满足');vi.mocked(researchAction).mockRejectedValueOnce(Error('项目已被其他操作更新，请重新载入核对'));await button('保存记录',dialog()).trigger('click');await flushPromises();expect(dialog().text()).toContain('其他操作更新');expect((dialog().get('textarea[aria-label="供应商更换原因"]').element as HTMLTextAreaElement).value).toBe('原供方工艺不能满足');
 });
 it('hides changes on earlier read-only steps and revoked supplier permissions',async()=>{
  choices();const d=withSupplier(5);await launch(d,1);expect(wrapper.text()).not.toContain('更换供应商');wrapper.unmount();d.canChangeSupplier=false;await launch(d);expect(wrapper.text()).not.toContain('更换供应商');
 });
 it('rejects the original engineer’s change after the login identity changes',async()=>{
  choices();await launch(withSupplier(5));await button('更换供应商').trigger('click');await flushPromises();await chooseReplacement();await dialog().get('textarea[aria-label="供应商更换原因"]').setValue('技术无法实现');useUserStore().token='another-session';await button('保存记录',dialog()).trigger('click');await flushPromises();expect(researchAction).not.toHaveBeenCalled();expect(dialog().text()).toContain('登录权限已变化');
 });
 it('allows an explicit pending replacement without requiring a fabricated next supplier',async()=>{
  choices();const d=withSupplier();await launch(d);await button('更换供应商').trigger('click');await flushPromises();dialog().getComponent(ElCheckbox).vm.$emit('update:modelValue',true);await flushPromises();await dialog().get('textarea[aria-label="供应商更换原因"]').setValue('供应商技术无法实现，继续寻找');
  const updated=structuredClone(d);updated.workflow.version=3;updated.workflow.planDraft.supplierAssignments=[];updated.supplierFeedbackSummary={cannotAcceptSupplierCount:0,nextSupplierPending:true,hasFeedback:false,restricted:false};updated.events=[{eventId:91,kind:'supplier_change',data:{replacementPending:true,before:{supplierAssignments:[assignment()]},after:{supplierAssignments:[]}},createdAt:'2026-10-23',createdBy:'工程师'}];vi.mocked(researchAction).mockResolvedValue(updated);await button('保存记录',dialog()).trigger('click');await flushPromises();expect(researchAction).toHaveBeenCalledWith(42,'supplier_change',2,expect.objectContaining({supplierCode:'',replacementPending:true,previousSupplierCode:'SUP-A',role:'mold',scope:'本次开发'}));expect(wrapper.getComponent(SupplierFeedbackPanel).props('defaultSupplierCode')).toBeUndefined();expect(wrapper.get('[data-readiness-target="supplier"]').getComponent(ElSelect).props('placeholder')).toBe('下一家待定');expect(wrapper.get('[aria-label="供应商反馈"]').text()).toContain('开模供应商：下一家待定');expect(wrapper.find('.test-dialog').exists()).toBe(false);
 });
 it.each([1,2,3,4,5,6,7,8])('keeps the same project feedback and exact file version visible at step %s',async step=>{
  choices();const d=withSupplier(step);d.workflow.planDraft.supplierAssignments=[];if(step>1)d.workflow.plan.supplierAssignments=[];d.supplierFeedbackSummary={cannotAcceptSupplierCount:1,nextSupplierPending:true,hasFeedback:true,restricted:false};d.events=[{eventId:91,kind:'supplier_change',data:{replacementPending:true,before:{supplierAssignments:[assignment()]},after:{supplierAssignments:[]}},createdAt:'2026-10-23',createdBy:'工程师'}];d.supplierFeedback=[{feedbackId:88,eventId:88,revision:1,stage:1,supplierSnapshot:{supplierCode:'SUP-A',supplierName:'原技术供方'},result:'cannot_accept',reason:'PC倒扣无法实现',scope:'PC背板',references:[d.materials[0]!],recordedAt:'2026-10-23',recordedBy:'工程师'}];await launch(d);expect(wrapper.get('[aria-label="供应商反馈"]').text()).toContain('PC倒扣无法实现');expect(wrapper.get('[aria-label="供应商反馈"]').text()).toContain('开模供应商：下一家待定');expect(wrapper.get('[aria-label="供应商反馈"]').text()).toContain('结构图.pdf · D2');
 });
 it('saves feedback through the current workflow version without overwriting unsaved project requirements',async()=>{
  choices();const d=withSupplier();await launch(d);await wrapper.get('[data-readiness-target="requirements"] textarea').setValue('保留尚未保存的开发要求');const panel=wrapper.getComponent(SupplierFeedbackPanel);const updated=structuredClone(d);updated.workflow.version=3;vi.mocked(researchAction).mockResolvedValue(updated);panel.vm.$emit('save',{supplierCode:'SUP-B',result:'cannot_accept',reason:'B也无法承接',feedbackDate:null,references:[{type:'file',id:11}],releaseCurrent:false},panel.props('context'));await flushPromises();expect(researchAction).toHaveBeenCalledWith(42,'supplier_feedback',2,{stage:1,supplierCode:'SUP-B',result:'cannot_accept',reason:'B也无法承接',feedbackDate:null,references:[{type:'file',id:11}],releaseCurrent:false},expect.any(String));expect((wrapper.get('[data-readiness-target="requirements"] textarea').element as HTMLTextAreaElement).value).toBe('保留尚未保存的开发要求');expect(panel.props('defaultSupplierCode')).toBe('SUP-A');expect(saveResearchDesignDraft).not.toHaveBeenCalled();
 });
 it('reuses the project upload queue for feedback documents and keeps the feedback input open',async()=>{
  choices();await launch(withSupplier());await button('记录反馈').trigger('click');await flushPromises();const feedback=wrapper.get('.test-dialog[data-title="记录供应商反馈"]');await feedback.get('textarea[aria-label="反馈说明"]').setValue('待补现场照片');await button('从本地上传反馈图片／附件',feedback).trigger('click');await flushPromises();expect(wrapper.findComponent(ElUpload).exists()).toBe(true);expect((feedback.get('textarea[aria-label="反馈说明"]').element as HTMLTextAreaElement).value).toBe('待补现场照片');expect(uploadProjectAttachment).not.toHaveBeenCalled();
 });
 it('keeps an unsaved feedback dialog when a step change is cancelled',async()=>{
  choices();const router=await launch(withSupplier());await button('记录反馈').trigger('click');await flushPromises();await wrapper.get('textarea[aria-label="反馈说明"]').setValue('正在记录，尚未保存');vi.spyOn(ElMessageBox,'confirm').mockRejectedValueOnce('cancel');await router.push('/product-research?step=drawing&productId=42');await flushPromises();expect(router.currentRoute.value.query.step).toBe('initiation');expect((wrapper.get('textarea[aria-label="反馈说明"]').element as HTMLTextAreaElement).value).toBe('正在记录，尚未保存');expect(researchAction).not.toHaveBeenCalled();
 });

 it('does not install an old feedback response after the login identity changes during saving',async()=>{
  choices();const d=withSupplier();await launch(d);let finish!:(value:ProductResearchDetail)=>void;vi.mocked(researchAction).mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve;}));const panel=wrapper.getComponent(SupplierFeedbackPanel);panel.vm.$emit('save',{supplierCode:'SUP-B',result:'pending',reason:'待反馈',references:[]},panel.props('context'));await flushPromises();useUserStore().token='different-session';const updated=structuredClone(d);updated.productName='不应带入新登录的旧响应';updated.workflow.version=3;finish(updated);await flushPromises();expect(wrapper.get('.project-header').text()).not.toContain('不应带入新登录的旧响应');expect(wrapper.text()).toContain('重新载入核对保存结果');
 });

 it('releases only its exact supplier target while keeping other unsaved engineering assignments intact',async()=>{
  choices();const d=withSupplier(7);await launch(d);const pending={mode:'internal',internalName:'尚未保存的协作方',role:'component',scope:'按键',basis:'待核对的输入'};wrapper.getComponent(EngineeringBasisPanel).vm.$emit('update:assignments',[assignment(),pending]);await flushPromises();const updated=structuredClone(d);updated.workflow.version=3;updated.workflow.planDraft.supplierAssignments=[];updated.workflow.plan.supplierAssignments=[];vi.mocked(researchAction).mockResolvedValue(updated);const panel=wrapper.getComponent(SupplierFeedbackPanel);panel.vm.$emit('save',{supplierCode:'SUP-A',result:'cannot_accept',reason:'技术无法实现',references:[],releaseCurrent:true,previousSupplierCode:'SUP-A',role:'mold',assignmentScope:'本次开发'},panel.props('context'));await flushPromises();expect(wrapper.getComponent(EngineeringBasisPanel).props('assignments')).toEqual([pending]);expect(saveResearchDesignDraft).not.toHaveBeenCalled();
 });

 it('uses the shared server pending summary instead of interpreting legacy event history',async()=>{
  choices();const d=withSupplier();d.workflow.planDraft.supplierAssignments=[];d.supplierFeedbackSummary={cannotAcceptSupplierCount:2,nextSupplierPending:true,hasFeedback:true,restricted:false};d.events=[];await launch(d);expect(wrapper.get('[data-readiness-target="supplier"]').getComponent(ElSelect).props('placeholder')).toBe('下一家待定');expect(wrapper.get('[aria-label="供应商反馈"]').text()).toContain('开模供应商：下一家待定');
 });

 it('retries a committed feedback with the same request ID after its response is lost and keeps the entered text',async()=>{
  choices();const d=withSupplier();await launch(d);let first=true;const saved=new Set<string>();const updated=structuredClone(d);updated.workflow.version=3;vi.mocked(researchAction).mockImplementation(async(_id,_action,_version,_input,requestId)=>{saved.add(requestId!);if(first){first=false;throw Error('已提交但响应丢失，请重试');}return updated;});await button('记录反馈').trigger('click');await flushPromises();const feedback=wrapper.get('.test-dialog[data-title="记录供应商反馈"]');await feedback.get('textarea[aria-label="反馈说明"]').setValue('供应商仍在评估，保留原沟通内容');await button('保存反馈',feedback).trigger('click');await flushPromises();expect((feedback.get('textarea[aria-label="反馈说明"]').element as HTMLTextAreaElement).value).toBe('供应商仍在评估，保留原沟通内容');expect(feedback.text()).toContain('响应丢失');await button('保存反馈',feedback).trigger('click');await flushPromises();const calls=vi.mocked(researchAction).mock.calls;expect(calls).toHaveLength(2);expect(calls[0]![4]).toBe(calls[1]![4]);expect(saved.size).toBe(1);expect(wrapper.find('.test-dialog[data-title="记录供应商反馈"]').exists()).toBe(false);
 });
 it('assigns a new feedback request ID when its content or login context changes',async()=>{
  choices();await launch(withSupplier());vi.spyOn(ElMessageBox,'confirm').mockResolvedValue(Object.assign('confirm' as const,{action:'confirm' as const,value:''}));vi.mocked(researchAction).mockRejectedValue(Error('网络未返回结果'));await button('记录反馈').trigger('click');await flushPromises();let feedback=wrapper.get('.test-dialog[data-title="记录供应商反馈"]');await feedback.get('textarea[aria-label="反馈说明"]').setValue('第一份输入');await button('保存反馈',feedback).trigger('click');await flushPromises();await feedback.get('textarea[aria-label="反馈说明"]').setValue('已补充技术内容');await button('保存反馈',feedback).trigger('click');await flushPromises();await button('取消',feedback).trigger('click');await flushPromises();useUserStore().token='new-session';await flushPromises();await button('记录反馈').trigger('click');await flushPromises();feedback=wrapper.get('.test-dialog[data-title="记录供应商反馈"]');await feedback.get('textarea[aria-label="反馈说明"]').setValue('已补充技术内容');await button('保存反馈',feedback).trigger('click');await flushPromises();const calls=vi.mocked(researchAction).mock.calls;expect(calls).toHaveLength(3);expect(new Set(calls.map(c=>c[4])).size).toBe(3);expect(calls[1]![3]).toEqual(calls[2]![3]);
 });
 it('starts a new feedback request ID after the preceding identical feedback was saved successfully',async()=>{
  choices();const d=withSupplier();await launch(d);vi.mocked(researchAction).mockImplementation(async()=>{const updated=structuredClone(d);updated.workflow.version=2+vi.mocked(researchAction).mock.calls.length;return updated;});for(let i=0;i<2;i++){await button('记录反馈').trigger('click');await flushPromises();const feedback=wrapper.get('.test-dialog[data-title="记录供应商反馈"]');await feedback.get('textarea[aria-label="反馈说明"]').setValue('新一轮沟通仍在评估');await button('保存反馈',feedback).trigger('click');await flushPromises();expect(wrapper.find('.test-dialog[data-title="记录供应商反馈"]').exists()).toBe(false);}const calls=vi.mocked(researchAction).mock.calls;expect(calls).toHaveLength(2);expect(calls[0]![4]).not.toBe(calls[1]![4]);expect(calls[0]![3]).toEqual(calls[1]![3]);expect(calls.map(call=>call[2])).toEqual([2,3]);
 });

});


describe('approved project layout retains original engineering entries',()=>{
 it('places basic fields, requirements, files and cost planning in one reading and keyboard order',async()=>{
  await launch();
  const content=wrapper.get('.form-area');
  expect(content.findAll('h3').map(h=>h.text())).toEqual(['基本资料与开发需求','供应商反馈','图片与附件','成本与计划']);
  expect(wrapper.get('.basic-section').findAll('.el-form-item__label').map(label=>label.text())).toEqual(['产品名称','项目负责人','开发需求','意向开模供应商']);
  expect(content.findAll('[data-readiness-target]').map(node=>node.attributes('data-readiness-target')).filter(target=>['requirements','product-name','owner','drawings'].includes(target!))).toEqual(['product-name','owner','requirements','drawings']);
  expect(wrapper.get('[data-readiness-target="owner"]').getComponent(ElSelect).props('placeholder')).toBe('请选择负责推进这款新品的人');
  expect(wrapper.get('.project-header').findAll('button').map(b=>b.text())).toEqual(['返回列表','发起LEMOS开模申请','成本','项目操作']);
  expect(wrapper.find('.stage-caption').exists()).toBe(false);
 });
 it('keeps every original optional estimate and multiline note in the same draft transaction',async()=>{
  await launch();await fields('初步材料／部件').get('textarea').setValue('待供应商核对\nPC 与 TPU 方案');await fields('预计周期').get('input').setValue('约8周');await fields('补充说明').get('textarea').setValue('报价待确认\n不代填实际结论');
  await button('保存草稿').trigger('click');await flushPromises();
  expect(saveResearchDesignDraft).toHaveBeenCalledWith(42,expect.objectContaining({notes:'报价待确认\n不代填实际结论'}),expect.objectContaining({preliminaryMaterials:'待供应商核对\nPC 与 TPU 方案',estimatedDuration:'约8周'}));
  expect(fields('目标完成日期').exists()).toBe(true);expect(fields('目标单件成本').exists()).toBe(true);
 });
 it('lets a future step be viewed without exposing write or confirmation actions',async()=>{
  const router=await launch();await wrapper.get('.research-step-nav button[aria-label="3D打样，未完成，可查看"]').trigger('click');await flushPromises();
  expect(router.currentRoute.value.query.step).toBe('prototype');expect(wrapper.get('.readonly-notice').text()).toContain('本页仅查看');expect(wrapper.find('.save-bar').exists()).toBe(false);expect(wrapper.findAll('button').some(button=>button.text()==='登记实际收样')).toBe(false);expect(wrapper.findComponent(ResearchMaterialsPanel).props('editable')).toBe(false);
 });
 it('marks only completed timeline evidence and leaves later steps available for read-only viewing',async()=>{
  const d=current(3);vi.mocked(getProjectTimeline).mockResolvedValue({projectId:42,currentStepNo:3,nodes:PRODUCT_RESEARCH_STAGES.map(s=>({stepNo:s.step,nodeKey:`RESEARCH_${s.key}`,nodeName:s.name,status:s.step<3?'completed':s.step===3?'current':'pending',confirmed:s.step<3}))} as any);
  await launch(d);expect(wrapper.findAll('.research-step-nav .done')).toHaveLength(2);expect(wrapper.get('.research-step-nav .current').attributes('aria-current')).toBe('step');expect(wrapper.get('.research-step-nav button[aria-label="发模，未完成，可查看"]').attributes('disabled')).toBeUndefined();expect(wrapper.get('.research-step-nav .done .step-complete-mark').text()).toBe('✓');
 });
 it('keeps long sample issues auto-growing and preserves their full original text',async()=>{
  const d=current(3);d.rounds=[];await launch(d);const inputs=wrapper.findAllComponents(ElInput).filter(input=>input.props('type')==='textarea');expect(inputs.length).toBeGreaterThan(2);expect(inputs.every(input=>!!input.props('autosize'))).toBe(true);
  const longText='工程师记录\n'+('结构问题与供应商处理意见，'.repeat(70));await fields('本次改动').get('textarea').setValue(longText);await button('保存草稿').trigger('click');await flushPromises();expect(saveDevelopmentRound).toHaveBeenCalledWith(42,null,expect.objectContaining({changes:longText,confirm:false}));
 });
 it('retains logs in project actions while omitting draft deletion on read-only history',async()=>{
  await launch(current(7),1);await wrapper.get('button[aria-label="项目操作"]').trigger('click');await flushPromises();const actions=wrapper.findAllComponents(ElDropdownItem).map(item=>item.text());expect(actions).toContain('系统日志');expect(actions).toContain('定位资料（1）');expect(actions).not.toContain('删除新品草稿');
 });
});


describe('mold execution opens with the physical set overview',()=>{
 it('defaults to making and trial progress instead of hiding physical sets behind trial samples',async()=>{
  const d=current(6);d.molds=[{moldSetId:7,assetNumber:'QA-PC-7',development:{node:'making',version:1}},{moldSetId:8,assetNumber:'QA-TPU-8',development:{node:'testing',version:1}}];await launch(d);
  expect((wrapper.get('.toolbar .el-radio-button input[value="mold"]').element as HTMLInputElement).checked).toBe(true);expect(wrapper.findAll('.mold-row')).toHaveLength(2);expect(wrapper.get('[data-readiness-target="mold:7"]').text()).toContain('QA-PC-7');expect(wrapper.get('[data-readiness-target="mold:8"]').text()).toContain('QA-TPU-8');expect(wrapper.find('.sample-form').exists()).toBe(false);expect(saveDevelopmentRound).not.toHaveBeenCalled();
 });
 it('locates a trial-sample gap by switching the existing pane without creating another sample',async()=>{
  vi.mocked(checkResearchStage).mockResolvedValue({version:2,ready:false,checks:[missing('sample','试模样品')]});await launch(current(6));await wrapper.getComponent(StageReadinessChecklist).vm.checkNow();await flushPromises();expect(wrapper.find('.sample-form').exists()).toBe(false);
  await wrapper.get('button[aria-label="补齐试模样品"]').trigger('click');await flushPromises();expect((wrapper.get('.toolbar .el-radio-button input[value="sample"]').element as HTMLInputElement).checked).toBe(true);expect(wrapper.find('.sample-form').exists()).toBe(true);expect(saveDevelopmentRound).not.toHaveBeenCalled();expect(researchAction).not.toHaveBeenCalled();
 });
 it('keeps unsaved trial-sample input in its pane when the original save-before-switch request fails',async()=>{
  const d=current(6);d.rounds=[{...record().rounds[0],roundId:81,status:'draft',result:'pending',details:{samplePurpose:'mold_trial'}}];await launch(d);await wrapper.get('.toolbar .el-radio-button input[value="sample"]').setValue();await flushPromises();await fields('本次改动').get('textarea').setValue('保留尚未保存的试模改动');vi.mocked(saveDevelopmentRound).mockRejectedValueOnce(Error('保存失败，继续编辑'));
  await wrapper.get('.toolbar .el-radio-button input[value="mold"]').setValue();await flushPromises();expect(wrapper.find('.sample-form').exists()).toBe(true);expect((fields('本次改动').get('textarea').element as HTMLTextAreaElement).value).toBe('保留尚未保存的试模改动');expect(saveDevelopmentRound).toHaveBeenCalledWith(42,81,expect.objectContaining({changes:'保留尚未保存的试模改动',confirm:false}));expect(wrapper.text()).toContain('保存失败，继续编辑');
 });
});

describe('explicit 3D product drawing materials',()=>{
 const material3d=(id:number,category='drawing',fileExt='step')=>({type:'file',id,name:`三维资料${id}.${fileExt}`,versionNo:'D3',status:'draft',category,attachment:{attachmentId:id,fileExt,fileCategory:category,originalFileName:`三维资料${id}.${fileExt}`,versionNo:'D3'}} as any);
 it('locates the specific 3D material uploader from the nonblocking drawing reminder',async()=>{
  vi.mocked(checkResearchStage).mockResolvedValue({version:2,ready:true,checks:[{code:'PRODUCT_3D_DRAWING',title:'3D产品图',target:'drawings',complete:false,blocking:false,message:'3D图可后补'}]});await launch(current(2));await wrapper.getComponent(StageReadinessChecklist).vm.checkNow();await flushPromises();const entry=wrapper.get('[aria-label="3D产品图资料"]'),scroll=vi.fn(),focus=vi.fn();Object.defineProperty(entry.element,'scrollIntoView',{value:scroll,configurable:true});Object.defineProperty(entry.get('[data-readiness-focus="product3d-upload"]').element,'focus',{value:focus,configurable:true});await wrapper.get('button[aria-label="补充3D产品图"]').trigger('click');await flushPromises();expect(scroll).toHaveBeenCalled();expect(focus).toHaveBeenCalled();expect(uploadProjectAttachment).not.toHaveBeenCalled();expect(confirmTimelineNode).not.toHaveBeenCalled();
 });
 it.each([['source','上传3D源文件','drawing','.step'],['render','上传3D效果图','render_3d','.png']])('uses the original upload and adopted-ID pipeline for explicit %s documents',async(kind,label,category,extension)=>{
  const d=current(2);await launch(d);const notes=wrapper.get('textarea[placeholder="说明本次产品图的结构、功能及版本改动"]');await notes.setValue('保留尚未保存的图纸说明');await button(label!).trigger('click');await flushPromises();const dialog=wrapper.get(`.test-dialog[data-title="${label}"]`),uploader=dialog.getComponent(ElUpload);expect(uploader.props('accept')).toContain(extension);if(kind==='source'){for(const ext of ['.png','.pdf','.x_t','.x_b','.3mf'])expect(String(uploader.props('accept')).split(',')).not.toContain(ext);}else expect(String(uploader.props('accept')).split(',')).toEqual(['.png','.jpg','.jpeg','.webp','.pdf']);const file=new File(['3d'],`明确用途${extension}`);Object.assign(file,{uid:96});uploader.vm.$emit('update:fileList',[{uid:96,name:file.name,raw:file}]);await flushPromises();vi.mocked(uploadProjectAttachment).mockResolvedValueOnce({attachmentId:96} as any);vi.mocked(getProductResearchDetail).mockResolvedValue({...d,materials:[...d.materials,material3d(96,category,extension!.slice(1))]});await button('保存版本',dialog).trigger('click');await flushPromises();expect(uploadProjectAttachment).toHaveBeenCalledWith(42,file,{fileCategory:category,versionNo:'V1',remark:''});expect(wrapper.getComponent(Product3dMaterials).props('selected')).toContain('file:96');expect(wrapper.getComponent(Product3dMaterials).text()).toContain('本次待保存采用');expect(wrapper.getComponent(Product3dMaterials).props('savedReferences')).toEqual([]);expect((notes.element as HTMLTextAreaElement).value).toBe('保留尚未保存的图纸说明');expect(researchAction).not.toHaveBeenCalled();await button('保存草稿').trigger('click');await flushPromises();expect(researchAction).toHaveBeenCalledWith(42,'launch_draft',2,expect.objectContaining({stage:2,references:[{type:'file',id:96}]}));expect(d.workflow.stage2Basis).toBeUndefined();
 });
 it('cancels the dedicated uploader and rejects ordinary PNG files in its source route without classifying or adopting them',async()=>{
  await launch(current(2));await button('上传3D源文件').trigger('click');await flushPromises();await button('关闭',wrapper.get('.test-dialog[data-title="上传3D源文件"]')).trigger('click');expect(uploadProjectAttachment).not.toHaveBeenCalled();await button('上传3D源文件').trigger('click');await flushPromises();const dialog=wrapper.get('.test-dialog[data-title="上传3D源文件"]'),file=new File(['png'],'普通图片.png');Object.assign(file,{uid:97});dialog.getComponent(ElUpload).vm.$emit('update:fileList',[{uid:97,name:file.name,raw:file}]);await button('保存版本',dialog).trigger('click');await flushPromises();expect(uploadProjectAttachment).not.toHaveBeenCalled();expect(wrapper.getComponent(Product3dMaterials).props('selected')).toEqual([]);expect(dialog.get('[role=alert]').text()).toContain('普通图片.png');expect(confirmTimelineNode).not.toHaveBeenCalled();
 });
 it('inherits a render file family and category when uploading a replacement version, preserving the original adoption snapshot',async()=>{
  const d=current(2),render=material3d(22,'render_3d','pdf');d.materials=[render];d.workflow.stageDrafts={2:{references:[{type:'file',id:22}]}};await launch(d);wrapper.getComponent(ResearchMaterialsPanel).vm.$emit('upload',render);await flushPromises();const dialog=wrapper.get('.test-dialog[data-title="上传新版本"]');expect(dialog.getComponent(ElUpload).props('accept')).toBe('.png,.jpg,.jpeg,.webp,.pdf');const file=new File(['pdf'],'新效果图.pdf');Object.assign(file,{uid:98});dialog.getComponent(ElUpload).vm.$emit('update:fileList',[{uid:98,name:file.name,raw:file}]);await dialog.get('input[placeholder="如 D1、V2"]').setValue('D4');await dialog.get('textarea').setValue('调整外观渲染');vi.mocked(uploadAttachmentVersion).mockResolvedValueOnce({attachmentId:98} as any);await button('保存版本',dialog).trigger('click');await flushPromises();expect(uploadAttachmentVersion).toHaveBeenCalledWith(22,file,'D4','调整外观渲染');expect(uploadProjectAttachment).not.toHaveBeenCalled();expect(d.workflow.stageDrafts[2].references).toEqual([{type:'file',id:22}]);
 });
 it('keeps 3D upload and adoption disabled in a readonly historical stage and a received prototype immutable',async()=>{
  const d=current(3);d.materials=[material3d(21),material3d(22,'render_3d','png')];d.rounds=[{...record().rounds[0],roundId:81,status:'confirmed',result:'pending',details:{samplePurpose:'prototype_3d',phoneModelIds:[1]},references:[d.materials[0]]}];await launch(d);expect(wrapper.getComponent(Product3dMaterials).props('selectable')).toBe(false);expect(wrapper.getComponent(Product3dMaterials).props('savedReferences')).toEqual([d.materials[0]]);wrapper.unmount();await launch(d,2);expect(wrapper.getComponent(Product3dMaterials).props('uploadable')).toBe(false);expect(wrapper.getComponent(Product3dMaterials).props('selectable')).toBe(false);expect(wrapper.find('[data-readiness-focus="product3d-upload"]').exists()).toBe(false);expect(uploadProjectAttachment).not.toHaveBeenCalled();expect(saveDevelopmentRound).not.toHaveBeenCalled();
 });
});

it('retries only failed explicitly designated renders while retaining pending adopted IDs and unsaved project input',async()=>{
 const d=current(2),image=new File(['png'],'工程师效果图.png'),pdf=new File(['pdf'],'工程师效果图.pdf');await launch(d);const notes=wrapper.get('textarea[placeholder="说明本次产品图的结构、功能及版本改动"]');await notes.setValue('重试期间保留');await button('上传3D效果图').trigger('click');await flushPromises();const dialog=wrapper.get('.test-dialog[data-title="上传3D效果图"]');Object.assign(image,{uid:101});Object.assign(pdf,{uid:102});dialog.getComponent(ElUpload).vm.$emit('update:fileList',[{uid:101,name:image.name,raw:image},{uid:102,name:pdf.name,raw:pdf}]);vi.mocked(uploadProjectAttachment).mockResolvedValueOnce({attachmentId:101} as any).mockRejectedValueOnce(Error('效果图网络失败')).mockResolvedValueOnce({attachmentId:102} as any);await button('保存版本',dialog).trigger('click');await flushPromises();expect(dialog.get('[role=alert]').text()).toContain('效果图网络失败');expect(wrapper.getComponent(Product3dMaterials).props('selected')).toEqual(['file:101']);await button('继续上传',dialog).trigger('click');await flushPromises();expect(vi.mocked(uploadProjectAttachment).mock.calls.map(call=>[call[1].name,call[2].fileCategory])).toEqual([[image.name,'render_3d'],[pdf.name,'render_3d'],[pdf.name,'render_3d']]);expect(wrapper.getComponent(Product3dMaterials).props('selected')).toEqual(['file:101','file:102']);expect((notes.element as HTMLTextAreaElement).value).toBe('重试期间保留');expect(researchAction).not.toHaveBeenCalled();expect(confirmTimelineNode).not.toHaveBeenCalled();
});
