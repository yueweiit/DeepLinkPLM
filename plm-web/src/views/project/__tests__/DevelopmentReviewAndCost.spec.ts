import {defineComponent,h,ref,type Ref} from 'vue';
import { mount,flushPromises,type VueWrapper } from '@vue/test-utils';
import { createPinia,setActivePinia } from 'pinia';
import { createMemoryHistory,createRouter } from 'vue-router';
import ElementPlus,{ ElMessageBox,ElOption,ElFormItem,ElRadioGroup,ElForm,ElSelect,ElDatePicker } from 'element-plus';
import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest';
import { useUserStore } from '@/stores/user';
import { deleteDevelopmentDraft,getProductResearchDetail,previewUnitCost,researchAction,saveDevelopmentRound,type ProductResearchDetail,type UnitCostSheet } from '@/api/modules/productResearch';
import UnitCostSheetView from '../components/UnitCostSheet.vue';
import DevelopmentReviewPanel from '../components/DevelopmentReviewPanel.vue';
import EngineeringBasisPanel from '../components/EngineeringBasisPanel.vue';
vi.mock('@/api/modules/productResearch',async original=>({...await original<typeof import('@/api/modules/productResearch')>(),previewUnitCost:vi.fn(),researchAction:vi.fn(),saveDevelopmentRound:vi.fn(),deleteDevelopmentDraft:vi.fn(),getProductResearchDetail:vi.fn()}));
let wrapper:VueWrapper;let app:VueWrapper;let hostDetail:Ref<ProductResearchDetail>;
const cost=():UnitCostSheet=>({title:'透甲估算',currency:'CNY',displayCurrency:'MXN',exchangeRate:2.72,exchangeRateDate:null,taxBasis:'source',rows:[{id:'pc',category:'material',name:'PC',mode:'divide',unitPrice:13.8,allocationQty:16,description:'已包含水口和损耗',taxIncluded:true,taxRate:null}],references:[],totals:{base:null},missing:['加工成本待补'],warnings:[]});
const detail=():ProductResearchDetail=>({productId:42,productName:'透甲2.0',productCode:'RD-42',versionNo:'V1',model:'',requirements:'结构要求',targetCost:null,expectedDeliveryDate:null,ownerUserId:null,ownerName:'',currentStepNo:6,workflow:{version:8,unitCostDraft:cost(),reviewSetup:{version:1,tests:[{code:'drop',name:'跌落',requirement:'1.2m无破裂'}],plannedQty:1000,minYieldRate:null,fullInspection:true}},events:[],releases:[],status:'developing',canWrite:true,rounds:[],materials:[],release:null});
async function start(component:any,d=detail(),viewStage=d.currentStepNo){
 const pinia=createPinia();setActivePinia(pinia);const user=useUserStore();user.permissions=['*'];
 hostDetail=ref(d);
 const Host=defineComponent({setup:()=>()=>h(component,{detail:hostDetail.value,stage:viewStage,owners:[{userId:1,name:'测试工程师'}]})});
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/check',component:Host}]});await router.push('/check');await router.isReady();
 app=mount({template:'<router-view/>'},{global:{plugins:[pinia,router,ElementPlus],stubs:{ElDrawer:{props:['modelValue'],template:'<section v-if="modelValue"><slot/></section>'}}}});await flushPromises();wrapper=app.getComponent(component) as VueWrapper;

}
function button(text:string){const b=wrapper.findAll('button').find(b=>b.text()===text);expect(b,`button ${text}`).toBeTruthy();return b!;}
beforeEach(()=>{vi.clearAllMocks();vi.mocked(previewUnitCost).mockResolvedValue(cost());vi.mocked(researchAction).mockResolvedValue(detail());vi.mocked(getProductResearchDetail).mockResolvedValue(detail());vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as any);});
afterEach(()=>{app?.unmount();vi.restoreAllMocks();});
describe('single-cost spreadsheet actions',()=>{
 it('keeps unknown tax and FX dates blank and preserves inputs after a save failure',async()=>{
  await start(UnitCostSheetView);expect((wrapper.get('input[aria-label="汇率依据日期"]').element as HTMLInputElement).value).toBe('');
  await wrapper.get('input[aria-label="费用项目"]').setValue('PC原料');vi.mocked(researchAction).mockRejectedValueOnce(Error('项目已被其他操作更新'));
  await button('保存成本草稿').trigger('click');await flushPromises();expect(wrapper.text()).toContain('项目已被其他操作更新');expect((wrapper.get('input[aria-label="费用项目"]').element as HTMLInputElement).value).toBe('PC原料');
  expect(vi.mocked(researchAction).mock.calls[0].slice(0,3)).toEqual([42,'unit_cost_save',8]);expect(vi.mocked(researchAction).mock.calls[0][3]).toMatchObject({exchangeRateDate:null,rows:[{name:'PC原料',unitPrice:13.8,allocationQty:16,taxRate:null}]});
 });
 it('adds and deletes draft detail lines through visible controls and can delete the saved draft',async()=>{
  await start(UnitCostSheetView);await button('新建核算').trigger('click');await flushPromises();expect(wrapper.findAll('input[aria-label="费用项目"]')).toHaveLength(5);
  await wrapper.findAll('button[aria-label="删除费用明细"]')[0].trigger('click');await flushPromises();expect(wrapper.findAll('input[aria-label="费用项目"]')).toHaveLength(4);
  await button('删除草稿').trigger('click');await flushPromises();expect(researchAction).toHaveBeenCalledWith(42,'unit_cost_delete',8,{});expect(wrapper.emitted('updated')).toHaveLength(1);
 });
 it('shows confirmed versions read-only and copies them to a new editable draft',async()=>{
  const d=detail();d.workflow.unitCostVersions=[{...cost(),id:1,status:'confirmed',confirmedBy:'财务',confirmedAt:'2026-10-16'}];delete d.workflow.unitCostDraft;await start(UnitCostSheetView,d);
  const select=wrapper.findAllComponents({name:'ElSelect'})[0];select.vm.$emit('change','1');await flushPromises();expect(wrapper.text()).toContain('复制为新版本');expect(wrapper.find('input[aria-label="费用项目"]').exists()).toBe(false);
  await button('复制为新版本').trigger('click');await flushPromises();expect(wrapper.find('input[aria-label="费用项目"]').exists()).toBe(true);expect((wrapper.get('input[aria-label="成本修订原因"]').element as HTMLInputElement).value).toBe('');
 });
});
describe('review draft actions reuse development rounds',()=>{
 it('places owner sample confirmation before mold work and trial requirements only in trial',async()=>{
  const d=detail();d.currentStepNo=7;d.workflow.reviewRecords=[{id:1,gate:'design',status:'confirmed',result:'passed',title:'负责人已确认样品'}];
  await start(DevelopmentReviewPanel,d,4);expect(wrapper.get('.review-nav').text()).toBe('样品确认');
  for(const text of ['试产计划数量','最低良率','测试要求','试模确认','作废'])expect(wrapper.text()).not.toContain(text);
  expect(wrapper.text()).toContain('负责人已确认样品');
  app.unmount();await start(DevelopmentReviewPanel,d,6);expect(wrapper.text()).toContain('测试要求');expect(wrapper.text()).not.toContain('最低良率');
  app.unmount();await start(DevelopmentReviewPanel,d,7);expect(wrapper.text()).toContain('试产计划数量');expect(wrapper.text()).toContain('最低良率');expect(wrapper.find('input[aria-label="测试名称"]').exists()).toBe(false);
 });
 it('saves test requirements without sending trial fields from an earlier stage',async()=>{
  await start(DevelopmentReviewPanel);await button('保存验证要求').trigger('click');await flushPromises();
  expect(researchAction).toHaveBeenCalledWith(42,'review_setup',8,{tests:[{code:'drop',name:'跌落',requirement:'1.2m无破裂'}],reason:undefined});
 });
 it('saves a reliability draft without inventing a reviewer, date or passing result',async()=>{
  await start(DevelopmentReviewPanel);wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue','reliability');await flushPromises();await button('新增测试').trigger('click');await flushPromises();
  await button('保存评审草稿').trigger('click');await flushPromises();expect(saveDevelopmentRound).toHaveBeenCalledWith(42,null,expect.objectContaining({kind:'test',recordDate:null,result:'pending',confirm:false,version:8,details:expect.objectContaining({reviewerUserId:null,actualResult:'',tests:[expect.objectContaining({result:'pending',actual:'',sampleQty:null})]})}));
 });
 it('offers edit and delete for an existing trial draft and leaves confirmed history immutable',async()=>{
  const d=detail();d.currentStepNo=7;d.rounds=[{roundId:10,roundNo:1,kind:'pilot',recordDate:null,title:'试产草稿',result:'pending',issues:'',changes:'',retestResult:'',references:[],status:'draft',details:{phoneModelIds:[]}}];
  await start(DevelopmentReviewPanel,d);wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue','pilot');await flushPromises();expect(button('编辑').exists()).toBe(true);await button('删除草稿').trigger('click');await flushPromises();expect(deleteDevelopmentDraft).toHaveBeenCalledWith(42,10,8);expect(getProductResearchDetail).toHaveBeenCalledWith(42);
 });
});

describe('stage handoffs keep the original engineering evidence',()=>{
 it('confirms the received prototype with its owner and adopted files without creating another sample',async()=>{
  const d=detail();d.currentStepNo=4;d.ownerUserId=1;d.workflow.prototypeRoundId=11;d.rounds=[{roundId:11,roundNo:1,kind:'sample',status:'confirmed',result:'pending',recordDate:'2026-10-15',issues:'',changes:'',retestResult:'',title:'3D代表样',details:{samplePurpose:'prototype_3d',phoneModelIds:[1]},references:[{type:'file',id:21,name:'3D产品图',versionNo:'D1',status:'draft'}]}];d.workflow.requirement={productName:'透甲',model:'',targetCost:null,expectedDeliveryDate:null,requirements:'PC背板',phoneModelIds:[1],requirementVersion:1,references:[{type:'file',id:21}]};
  await start(DevelopmentReviewPanel,d);await button('新增评审').trigger('click');await flushPromises();
  expect(wrapper.text()).toContain('装配与功能方案可行');expect(wrapper.text()).not.toContain('装配与功能实测已核对');
  await button('保存评审草稿').trigger('click');await flushPromises();
  expect(researchAction).toHaveBeenCalledWith(42,'review_save',8,expect.objectContaining({gate:'design',sampleRoundId:11,reviewerUserId:1,result:'pending',reviewDate:null,references:[{type:'file',id:21}]}));
 });
 it('carries the adopted sample and its version IDs into reliability without copying a passing result',async()=>{
  const d=detail();d.workflow.validationSampleId=11;d.rounds=[{roundId:11,roundNo:1,kind:'sample',status:'confirmed',title:'已收样',result:'passed',recordDate:'2026-10-15',issues:'',changes:'',retestResult:'',details:{phoneModelIds:[7]},references:[{type:'bom',id:20,name:'用料清单',versionNo:'D1',status:'draft'}]}];
  await start(DevelopmentReviewPanel,d);wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue','reliability');await flushPromises();await button('新增测试').trigger('click');await flushPromises();await button('保存评审草稿').trigger('click');await flushPromises();
  expect(saveDevelopmentRound).toHaveBeenCalledWith(42,null,expect.objectContaining({references:[{type:'bom',id:20}],result:'pending',recordDate:null,details:expect.objectContaining({sourceRoundId:11,phoneModelIds:[7],actualResult:'',reviewerUserId:null})}));
 });
 it('uses the backend current reliability ID for trial rather than guessing the newest historical test',async()=>{
  const d=detail();d.currentStepNo=7;d.reviewGates=[{gate:'reliability',status:'passed',recordId:12,reason:'当前依据'}];
  d.rounds=[13,12].map(id=>({roundId:id,roundNo:id,kind:'test',status:'confirmed',title:'测试'+id,result:'passed',recordDate:'2026-10-15',issues:'',changes:'',retestResult:'',details:{phoneModelIds:[7]},references:[{type:'bom',id,name:'用料清单',versionNo:'D1',status:'draft'}]}));
  await start(DevelopmentReviewPanel,d);wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue','pilot');await flushPromises();await button('新增试产').trigger('click');await flushPromises();await button('保存评审草稿').trigger('click');await flushPromises();
  expect(saveDevelopmentRound).toHaveBeenCalledWith(42,null,expect.objectContaining({references:[{type:'bom',id:12}],details:expect.objectContaining({sourceRoundId:12,producedQty:null,inspectedQty:null,defectQty:null})}));
 });
 it('does not silently replace a saved cost draft when creating another sheet',async()=>{
  await start(UnitCostSheetView);vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel');await button('新建核算').trigger('click');await flushPromises();
  expect(wrapper.findAll('input[aria-label="费用项目"]')).toHaveLength(1);expect((wrapper.get('input[aria-label="费用项目"]').element as HTMLInputElement).value).toBe('PC');expect(researchAction).not.toHaveBeenCalled();
 });
});

describe('review page context after a save',()=>{
 it('keeps the selected review gate when a save refreshes the same project',async()=>{
  const d=detail();d.currentStepNo=7;await start(DevelopmentReviewPanel,d);wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue','pilot');await flushPromises();
  hostDetail.value={...d,workflow:{...d.workflow,version:9},rounds:[{roundId:99,roundNo:1,kind:'pilot',status:'draft',title:'刚保存的试产草稿',result:'pending',recordDate:null,issues:'',changes:'',retestResult:'',details:{phoneModelIds:[]},references:[]}]};await flushPromises();
  expect(wrapper.text()).toContain('刚保存的试产草稿');expect(button('编辑').exists()).toBe(true);expect(wrapper.findAll('input[aria-label="测试名称"]')).toHaveLength(0);
 });
});


describe('single-column child forms keep labels and original writes',()=>{
 function field(label:string){const item=wrapper.findAll('.el-form-item').find(item=>item.find('.el-form-item__label').exists()&&item.get('.el-form-item__label').text()===label);expect(item,`field ${label}`).toBeTruthy();return item!;}
 it('labels each test requirement and keeps numeric thresholds, units and reason in the original setup payload',async()=>{
  await start(DevelopmentReviewPanel);expect(wrapper.findAllComponents(ElForm).every(form=>form.props('labelPosition')==='top')).toBe(true);
  expect(wrapper.get('.test-requirement').findAll('.el-form-item__label').map(label=>label.text())).toEqual(['测试名称','测试要求','最低实测值','测量单位']);
  await field('最低实测值').get('input').setValue('7');await field('测量单位').get('input').setValue('N');await field('验证要求修改原因').get('input').setValue('补充磁吸力的判定单位');await button('保存验证要求').trigger('click');await flushPromises();
  expect(researchAction).toHaveBeenCalledWith(42,'review_setup',8,{tests:[{code:'drop',name:'跌落',requirement:'1.2m无破裂',minValue:7,unit:'N'}],reason:'补充磁吸力的判定单位'});expect(wrapper.findAll('.is-required')).toHaveLength(0);
 });
 it('labels actual measurements and dates while retaining pending conclusions and saving all original test details',async()=>{
  const d=detail();(d.workflow.reviewSetup as any).tests[0]={code:'magnet',name:'磁吸力',requirement:'不少于7N',minValue:7,unit:'N'};await start(DevelopmentReviewPanel,d);wrapper.findComponent(ElRadioGroup).vm.$emit('update:modelValue','reliability');await flushPromises();await button('新增测试').trigger('click');await flushPromises();
  const test=wrapper.get('.test-result');expect(test.findAll('.el-form-item__label').map(label=>label.text())).toEqual(['实际结果','实测值 / N','测试方法','测试样本数','测试人','测试日期','单项测试结论']);
  await field('实际结果').get('input').setValue('实测7.3N');await field('实测值 / N').get('input').setValue('7.3');await field('测试方法').get('input').setValue('拉力计');await field('测试样本数').get('input').setValue('3');await field('测试人').get('input').setValue('实际测试工程师');field('测试日期').getComponent(ElDatePicker).vm.$emit('update:modelValue','2026-10-23');await flushPromises();await button('保存评审草稿').trigger('click');await flushPromises();
  expect(saveDevelopmentRound).toHaveBeenCalledWith(42,null,expect.objectContaining({result:'pending',recordDate:null,confirm:false,details:expect.objectContaining({tests:[expect.objectContaining({actual:'实测7.3N',measuredValue:7.3,method:'拉力计',sampleQty:3,tester:'实际测试工程师',date:'2026-10-23',result:'pending'})]})}));
 });
 it('keeps trial inspection and its reason explicitly labelled without turning unknown yield into a required or passing value',async()=>{
  const d=detail();d.currentStepNo=7;await start(DevelopmentReviewPanel,d);expect(field('试产检验要求').exists()).toBe(true);expect(field('验证要求修改原因').exists()).toBe(true);field('试产检验要求').getComponent(ElSelect).vm.$emit('update:modelValue',false);await flushPromises();await button('保存验证要求').trigger('click');await flushPromises();expect(researchAction).toHaveBeenCalledWith(42,'review_setup',8,{plannedQty:1000,minYieldRate:null,fullInspection:false,reason:undefined});expect(wrapper.findAll('.is-required')).toHaveLength(0);
 });
 const engineeringProps=()=>({assignments:[{mode:'supplier',supplierCode:'S1',role:'component',scope:'PC背板',basis:''}],selected:['file:1','bom:6'],materials:[{type:'bom',id:7,name:'已核对BOM',versionNo:'B1',status:'draft'},{type:'process',id:8,name:'已核对工艺',versionNo:'P1',status:'draft'}],suppliers:[{supplierCode:'S1',supplierName:'测试供应商'}],editable:true,assignmentsEditable:true,canSelectSupplier:true,canConfirm:true,canManageSupplier:true,busy:false,reason:''});
 it('labels supplier scope, BOM and process selection and preserves their original shared reference identities',async()=>{
  app=mount(EngineeringBasisPanel,{props:engineeringProps() as any,global:{plugins:[ElementPlus]}});wrapper=app;expect(wrapper.findAllComponents(ElForm).every(form=>form.props('labelPosition')==='top')).toBe(true);
  for(const label of ['承担方式','供应商','承担职责','供货／制作范围','采用依据','采用 BOM 版本','采用工艺路线版本','工程方案确认说明'])expect(field(label).exists()).toBe(true);
  await field('供货／制作范围').get('input').setValue('PC背板与按键');field('采用 BOM 版本').getComponent(ElSelect).vm.$emit('update:modelValue','bom:7');await flushPromises();expect(wrapper.emitted('update:assignments')?.[0]).toEqual([[{mode:'supplier',supplierCode:'S1',role:'component',scope:'PC背板与按键',basis:''}]]);expect(wrapper.emitted('update:selected')).toEqual([[['file:1','bom:7']]]);await button('保存并确认工程方案').trigger('click');expect(wrapper.emitted('confirm')).toEqual([[true]]);expect(wrapper.findAll('.is-required')).toHaveLength(0);
 });
 it('preserves readonly engineering selectors while leaving the original version viewing entries available',async()=>{
  app=mount(EngineeringBasisPanel,{props:{...engineeringProps(),editable:false,assignmentsEditable:false,canConfirm:false} as any,global:{plugins:[ElementPlus]}});wrapper=app;expect(wrapper.findAllComponents(ElSelect).every(select=>select.props('disabled'))).toBe(true);expect(wrapper.find('input[aria-label="分工1范围"]').exists()).toBe(false);expect(wrapper.find('input[aria-label="工程方案确认说明"]').exists()).toBe(false);await button('查看 BOM').trigger('click');expect(wrapper.emitted('engineering')).toEqual([['bom']]);expect(wrapper.emitted('update:assignments')).toBeUndefined();expect(wrapper.emitted('confirm')).toBeUndefined();
 });
});

it('filters only mold assignment suppliers and preserves a saved unclassified choice as disabled',async()=>{
 const props={assignments:[{mode:'supplier',supplierCode:'OLD',role:'mold',scope:'整套',basis:'历史依据'},{mode:'supplier',supplierCode:'PARTNER',role:'component',scope:'部件',basis:''}],persistedAssignments:[],selected:[],materials:[],suppliers:[{supplierCode:'MOLD',supplierName:'开模公司',businessCategories:['mold']},{supplierCode:'PARTNER',supplierName:'合作公司',businessCategories:['partner_selection']},{supplierCode:'OLD',supplierName:'原公司'}],editable:true,assignmentsEditable:true,canSelectSupplier:true,canConfirm:false,canManageSupplier:true,busy:false,reason:''};
 app=mount(EngineeringBasisPanel,{props:props as any,global:{plugins:[ElementPlus]}});wrapper=app;
 const supplierChoices=wrapper.findAllComponents(ElFormItem).filter(item=>item.props('label')==='供应商').map(item=>item.getComponent(ElSelect));
 expect(supplierChoices[0]!.findAllComponents(ElOption).map(option=>option.props('value'))).toEqual(['MOLD','OLD']);expect(supplierChoices[0]!.findAllComponents(ElOption).find(option=>option.props('value')==='OLD')!.props('disabled')).toBe(true);
 expect(supplierChoices[1]!.findAllComponents(ElOption).map(option=>option.props('value'))).toEqual(['MOLD','PARTNER','OLD']);expect(wrapper.emitted('update:assignments')).toBeUndefined();
});
