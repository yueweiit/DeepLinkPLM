import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {createRouter,createMemoryHistory} from 'vue-router'
import ElementPlus,{ElButton,ElCheckbox,ElFormItem,ElInput,ElSelect} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import type {EngineeringPlan} from '@/api/modules/moldApplications'
import {copyEngineeringPlan,engineeringPlanIssues,initialEngineeringPlan} from '@/utils/engineeringPlan'
const api=vi.hoisted(()=>({getManufacturingRelations:vi.fn(),saveManufacturingRelation:vi.fn(),getManufacturingOptions:vi.fn()}))
vi.mock('@/api/modules/manufacturing',()=>api)
const references=vi.hoisted(()=>({getPhoneCompatibleMolds:vi.fn(),getPhoneModelOptions:vi.fn(),getPhoneModels:vi.fn(),getPhoneSelectionBrands:vi.fn()}))
vi.mock('@/api/modules/phoneReference',()=>references)
const molds=vi.hoisted(()=>({getMoldOptions:vi.fn()}))
vi.mock('@/api/modules/molds',()=>molds)
import EngineeringPlanEditor from '@/views/mold-applications/components/EngineeringPlanEditor.vue'
import ManufacturingRelationsPanel from '../components/ManufacturingRelationsPanel.vue'
import ManufacturingRelationPicker from '../components/ManufacturingRelationPicker.vue'
import PhoneObjectIdSelect from '@/components/PhoneObjectSelect/IdSelect.vue'
import SharedCompatiblePhoneSelector from '../components/SharedCompatiblePhoneSelector.vue'
const phone={id:18,phoneModelId:18,brandId:1,brandName:'Apple',modelName:'Apple iPhone 18 Pro Max',phoneModelCode:'1218',codeItemId:1218,aliases:[],status:'enabled',codeLinkStatus:'linked'}
const relation={id:9,relationId:9,version:3,relationVersion:3,productLineId:50,itemId:41,moldSetId:42,physicalCode:'MS-QA-42',setNo:1,phoneModelId:18,holeType:'large',structureDescription:'大孔结构',sharedMaterial:true,manufacturingProductId:700,manufacturingProductCode:'FA1145',manufacturingProductName:'原17 Pro Max制造型号',manufacturingPhoneCode:'1145',skuIds:[701],skus:[{id:701,code:'NFA4030114502',name:'黑色成品'}],moldEvidence:'工程逐部件确认',materialEvidence:'实物、BOM和后加工一致',compatibilityDigest:'fit-v2',reason:'确认18适配',status:'active',businessPhone:phone,styleName:'骑士',moldName:'17/18 Pro Max大孔',canWrite:true}
let wrapper:VueWrapper|undefined
const uiStubs={Teleport:true,ElInput:{props:['modelValue','type'],emits:['update:modelValue'],template:'<textarea v-if="type===\'textarea\'" :value="modelValue" @input="$emit(\'update:modelValue\',$event.target.value)"/><input v-else :value="modelValue" @input="$emit(\'update:modelValue\',$event.target.value)"/>'},ElSelect:{props:['modelValue'],emits:['update:modelValue'],template:'<select :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value)"><slot /></select>'},ElOption:{props:['value','label','disabled'],template:'<option :value="value" :disabled="disabled">{{label}}</option>'}}
beforeEach(()=>{vi.clearAllMocks();setActivePinia(createPinia());api.getManufacturingRelations.mockResolvedValue({content:[relation],totalElements:1,page:1,size:20,totalPages:1});api.getManufacturingOptions.mockResolvedValue({content:[],totalElements:0});api.saveManufacturingRelation.mockResolvedValue({...relation,version:4});molds.getMoldOptions.mockResolvedValue({productLines:[{productLineId:50,productName:'骑士',productCode:'FA'}]});references.getPhoneCompatibleMolds.mockResolvedValue([{itemId:41,moldSetId:42,physicalCode:'MS-42',setNo:1,moldName:'17/18 Pro Max大孔',engravedCode:'1145',styleName:'骑士',compatibilityDigest:'fit-v2',parts:[{partType:'PC',code:'MFA201145'},{partType:'TPU',code:'MFA101145'}]}]);references.getPhoneModelOptions.mockResolvedValue([phone]);references.getPhoneModels.mockResolvedValue({content:[phone],totalElements:1});references.getPhoneSelectionBrands.mockResolvedValue([{id:1,brandId:1,name:'Apple',status:'enabled'}])})
afterEach(()=>{wrapper?.unmount();wrapper=undefined;document.body.innerHTML='';vi.restoreAllMocks()})
async function panel(editable=true){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:{template:'<div />'}},{path:'/molds',component:{template:'<div />'}}]});await router.push('/');wrapper=mount(ManufacturingRelationsPanel,{props:{phone,editable},global:{plugins:[ElementPlus,router],stubs:uiStubs},attachTo:document.body});await flushPromises()}
async function click(label:string){await wrapper!.findAllComponents(ElButton).find(button=>button.text()===label)!.trigger('click');await flushPromises()}
describe('explicit engineering scope',()=>{
 it('starts with an unfinished scope, preserves exact historical hole options, and never infers compatibility from names',()=>{
  const blank=initialEngineeringPlan();expect(blank.handling).toBe('pending');expect(engineeringPlanIssues(blank)).toEqual(['规格1：请明确孔型']);
  const known=initialEngineeringPlan({applicationId:1,executionIntent:'new',form:{holeType:'大孔Gran agujero'},phoneModelId:18});expect(known.specifications[0]!.holeType).toBe('large');expect(known.codePhoneModelId).toBe(18);
  expect(initialEngineeringPlan({applicationId:1,model:'17/18大孔通用',form:{}}).specifications[0]!.holeType).toBe('');
  const cloned=copyEngineeringPlan(known);cloned.specifications[0]!.phoneModelIds.push(17);expect(known.specifications[0]!.phoneModelIds).toEqual([18]);
 })
 it('uses the real scope editor for multiple phones and copies hole structure without carrying an old confirmation',async()=>{
  let plan:EngineeringPlan={handling:'new',specifications:[{key:'scope-a',holeType:'large',structureDescription:'大孔',phoneModelIds:[]}]};wrapper=mount(EngineeringPlanEditor,{props:{modelValue:plan,productLineId:50,'onUpdate:modelValue':(value:EngineeringPlan)=>{plan=value;void wrapper!.setProps({modelValue:value})}},global:{plugins:[ElementPlus],stubs:{...uiStubs,PhoneReferenceManager:true}}});wrapper.getComponent(SharedCompatiblePhoneSelector).vm.$emit('update:modelValue',[17,18]);await flushPromises();expect(plan.specifications[0]!.phoneModelIds).toEqual([17,18]);expect(plan.codePhoneModelId).toBeUndefined();await click('复制孔型，另选手机');expect(plan.specifications).toHaveLength(2);expect(plan.specifications[1]).toMatchObject({holeType:'large',phoneModelIds:[],structureDescription:'大孔'});expect(plan.specifications[1]!.manufacturingRelationId).toBeUndefined();
  plan={...plan,handling:'reuse'};await wrapper.setProps({modelValue:plan});expect(wrapper.findAllComponents(SharedCompatiblePhoneSelector)[0]!.props('multiple')).toBe(false);expect(engineeringPlanIssues(plan)).toContain('规格1：请为一款手机选择已确认的生产与模具关系');
  plan={...plan,handling:'new_set',specifications:[{...plan.specifications[0]!,phoneModelIds:[18]}]};await wrapper.setProps({modelValue:plan});api.getManufacturingRelations.mockResolvedValueOnce({content:[relation,{...relation,id:10,relationId:10,sharedMaterial:false}],totalElements:2,page:1,size:20});await click('选择已确认的生产与模具关系');const picker=wrapper.getComponent(ManufacturingRelationPicker);expect(picker.props('requireSharedMaterial')).toBe(true);const adopt=picker.findAllComponents(ElButton).filter(button=>button.text()==='采用此关系');expect(adopt[1]!.attributes('disabled')).toBeDefined();await adopt[0]!.trigger('click');await flushPromises();expect(plan.specifications[0]).toMatchObject({manufacturingRelationId:9,manufacturingRelationVersion:3});expect(wrapper.emitted('relationSelected')).toEqual([[relation]]);
 })
 it('requires a real other-structure explanation and keeps mold-only approval distinct from material sharing',()=>{
  const plan:EngineeringPlan={handling:'reuse',specifications:[{key:'a',holeType:'other',structureDescription:'',phoneModelIds:[18],manufacturingRelationId:9,manufacturingRelationVersion:3}]};expect(engineeringPlanIssues(plan)).toEqual(['规格1：其他孔型须填写结构说明']);plan.specifications[0]!.structureDescription='独立镜头框';expect(engineeringPlanIssues(plan)).toEqual([]);
 })
})
describe('manufacturing relation maintenance',()=>{
 it('shows business phone 1218 beside original manufacturing 1145 and a specific style, hole and set without a global replacement',async()=>{
  await panel(false);expect(api.getManufacturingRelations).toHaveBeenCalledWith(expect.objectContaining({phoneModelId:18,page:1,size:20,status:'all'}));expect(wrapper!.text()).toContain('Apple iPhone 18 Pro Max（1218）');expect(wrapper!.text()).toContain('制造编码 1145');expect(wrapper!.text()).toContain('17/18 Pro Max大孔 · 大孔');expect(wrapper!.text()).toContain('实物套 MS-QA-42');expect(wrapper!.text()).toContain('NFA4030114502');expect(wrapper!.text()).toContain('不是全局替代码');expect(wrapper!.findAll('button').some(button=>button.text()==='新增工程确认关系')).toBe(false);
 })
 it('updates the original versioned relation and clears material references when only shared tooling is confirmed',async()=>{
  await panel();await click('维护关系');expect(wrapper!.text()).toContain('PC MFA201145');expect(wrapper!.text()).toContain('TPU MFA101145');expect(wrapper!.text()).toContain('MS-42 · 第1套');wrapper!.getComponent(ElCheckbox).vm.$emit('update:modelValue',false);await flushPromises();const reason=wrapper!.findAllComponents(ElFormItem).find(item=>item.props('label')==='本次变更原因')!;reason.getComponent(ElInput).vm.$emit('update:modelValue','经核对仅共模，成品规格不同');await click('保存工程确认关系');expect(api.saveManufacturingRelation).toHaveBeenCalledWith(9,expect.objectContaining({version:3,phoneModelId:18,manufacturingProductId:null,skuIds:[],sharedMaterial:false,materialEvidence:'',compatibilityDigest:'fit-v2',reason:'经核对仅共模，成品规格不同'}));expect(wrapper!.emitted('changed')).toHaveLength(1);
 })
 it('does not default a new hole type and adopts the selected set digest without creating a physical set',async()=>{
  await panel();await click('新增工程确认关系');expect(wrapper!.findAllComponents(ElSelect).find(select=>select.attributes('aria-label')==='工程关系孔型')!.props('modelValue')).toBe('');const selectors=wrapper!.findAllComponents(PhoneObjectIdSelect);selectors[0]!.vm.$emit('update:modelValue',50);await flushPromises();selectors[1]!.vm.$emit('update:modelValue',42);await flushPromises();await click('保存工程确认关系');expect(wrapper!.text()).toContain('请明确孔型');expect(api.saveManufacturingRelation).not.toHaveBeenCalled();expect(references.getPhoneCompatibleMolds).toHaveBeenCalledWith(18,50);
 })
})
