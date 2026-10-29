import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {createMemoryHistory,createRouter} from 'vue-router'
import ElementPlus,{ElMessageBox,ElSelect} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
const api=vi.hoisted(()=>({partnerPage:vi.fn(),partnerDetail:vi.fn(),partnerSave:vi.fn(),partnerAction:vi.fn(),partnerOptions:vi.fn(),sampleMovements:vi.fn(),sampleSources:vi.fn(),newRequestId:vi.fn(()=> 'stable-receipt-request'),research:vi.fn()}))
vi.mock('@/api/modules/partner',()=>api)
vi.mock('@/api/modules/samples',()=>({researchSampleSources:api.research}))
vi.mock('../PartnerFiles.vue',()=>({default:{name:'SampleFilesStub',props:['files','canWrite','allowAdopt'],template:'<section class="test-files">图片与附件</section>'}}))
vi.mock('../PartnerPhoto.vue',()=>({default:{template:'<span>样品照片</span>'}}))
vi.mock('../PartnerImportDialog.vue',()=>({default:{template:'<div/>'}}))
vi.mock('../operations/PartnerOperationsPanel.vue',()=>({default:{props:['kind'],template:'<div class="test-qc">工厂质检 {{kind}}</div>'}}))
vi.mock('@/components/PhoneObjectSelect/IdSelect.vue',()=>({default:{props:['options','type'],emits:['update:modelValue'],template:'<div class="test-object" :data-type="type"><button v-for="o in options" :key="o.id" type="button" @click="$emit(\'update:modelValue\',o.id)">{{o.name}}</button></div>'}}))
vi.mock('@/views/mold/components/SharedCompatiblePhoneSelector.vue',()=>({default:{props:['modelValue'],emits:['update:modelValue'],template:'<button type="button" class="test-phone" @click="$emit(\'update:modelValue\',[185])">选择标准手机</button>'}}))
import Workspace from '../PartnerWorkspace.vue'
import SourceFields from '../components/ResearchSampleSourceFields.vue'
import BusinessFields from '../BusinessFields.vue'
import {useUserStore} from '@/stores/user'
let wrapper:VueWrapper
const row=(domain='model')=>({id:11,name:'QA实际样品',code:'SP-000011',sourceType:domain==='research'?'self':domain==='partner'?'partner':'trial',sampleDomain:domain,sampleKind:domain==='research'?'prototype':domain==='partner'?'supplier':'pc',status:'active',version:1,projectId:7,roundId:domain==='research'?91:undefined,specName:'0185 · PC',sourceSnapshot:{project:{id:7,name:'QA项目'},round:{no:1,title:'实际样品'},moldSet:{setNo:1}},ownerName:'QA保管',balances:{onhand:3,available:2,borrowed:1,retained:0,damaged:0,positions:[],loans:[]},files:[],events:[],refs:[]})
beforeEach(()=>{vi.restoreAllMocks();vi.clearAllMocks();localStorage.clear();setActivePinia(createPinia());useUserStore().token='qa';useUserStore().permissions=['*'];api.partnerOptions.mockResolvedValue({specs:[],suppliers:[],customers:[],canReadMoney:false,canWriteMoney:false});api.partnerPage.mockResolvedValue({content:[row()],totalElements:1,page:1,size:50});api.partnerDetail.mockResolvedValue(row());api.partnerSave.mockResolvedValue(row());api.sampleMovements.mockResolvedValue({content:[],totalElements:0});api.sampleSources.mockResolvedValue({projects:[],rounds:[],nodes:[]});api.research.mockImplementation(async(domain:string,id?:number)=>id?{...(domain==='model'?{project:{id,name:'QA项目',modelProductId:701,phoneModels:[{phoneModelId:185,modelName:'标准手机'}]}}:{}),rounds:[{id:91,no:1,title:'实际样品',date:'2026-10-22'}],sets:[]}:{products:[{id:701,name:'亮甲 2.0',salesStatus:'on_sale'}],projects:[{id:7,name:'QA项目',code:'QA-7'}]})})
afterEach(()=>{wrapper?.unmount();document.body.innerHTML=''})
async function start(path:string){
 const routes=['samples','model-samples','research-samples','partner-samples'].map((name,index)=>({path:'/'+name,component:Workspace,meta:{partnerKind:'samples',...(index?{sampleDomain:['model','research','partner'][index-1]}:{})}}))
 routes.push({path:'/product-research',component:{template:'<div>新品项目</div>'} as any,meta:{} as any});routes.push({path:'/partner-selections',component:{template:'<div>选品目录</div>'} as any,meta:{} as any});const router=createRouter({history:createMemoryHistory(),routes});await router.push(path);wrapper=mount({template:'<router-view/>'},{attachTo:document.body,global:{plugins:[router,ElementPlus],stubs:{ElDialog:{props:['modelValue'],template:'<section v-if="modelValue" class="test-dialog"><slot/><slot name="footer"/></section>'}}}});await flushPromises();return router
}
async function click(text:string){const b=wrapper.findAll('button').find(b=>b.text()===text);expect(b,`missing button ${text}`).toBeTruthy();await b!.trigger('click');await flushPromises()}
function receiptFields(){return wrapper.findAllComponents(BusinessFields).find(v=>v.props('fields').some((f:any)=>f.key==='qty'))!}
describe('three sample management entries, one physical ledger',()=>{
 it('opens a new research or model receipt directly from its own entry without a preselected project or any default quantity',async()=>{for(const [path,domain,type] of [['/research-samples?new=1','research','self'],['/model-samples?new=1','model','model']]){await start(path!);expect(wrapper.find('.test-dialog').exists()).toBe(true);const source=wrapper.getComponent(SourceFields);expect(source.props('domain')).toBe(domain);expect(source.props('modelValue').sourceType).toBe(type);expect(source.props('modelValue').projectId).toBeUndefined();expect(source.props('modelValue').qty).toBeUndefined();expect(api.partnerSave).not.toHaveBeenCalled();wrapper.unmount()}})
 it('does not turn a read-only receipt deep link into an editable form',async()=>{useUserStore().permissions=['partner:view'];await start('/research-samples?new=1');expect(wrapper.find('.test-dialog').exists()).toBe(false);expect(api.partnerSave).not.toHaveBeenCalled()})
 it('uses the model scope and project in both pages and keeps the same sample ID when opening a record',async()=>{
  const router=await start('/model-samples?projectId=7');expect(api.partnerPage).toHaveBeenLastCalledWith('samples',expect.objectContaining({domain:'model',projectId:'7',size:50}));expect(wrapper.text()).toContain('型号研发样品管理');expect(wrapper.text()).toContain('PC 部件样品');
  await click('流转记录');expect(api.sampleMovements).toHaveBeenLastCalledWith(expect.objectContaining({domain:'model',projectId:'7',size:50}));await click('样品台账');await click('查看／办理');expect(router.currentRoute.value.path).toBe('/model-samples');expect(router.currentRoute.value.query.id).toBe('11');expect(api.partnerDetail).toHaveBeenLastCalledWith('samples',11)
 })
 it('retries the same receipt after failure, keeps the form and confirms no planned quantity by default',async()=>{
  api.partnerSave.mockRejectedValueOnce(Error('网络超时，请核对后重试')).mockResolvedValueOnce(row('research'));api.partnerDetail.mockResolvedValue(row('research'));await start('/research-samples');await click('登记收样');
  const source=wrapper.getComponent(SourceFields);expect(source.props('domain')).toBe('research');expect(source.props('modelValue').qty).toBeUndefined();await source.get('.test-object button').trigger('click');await flushPromises();
  source.vm.$emit('update:modelValue',{...source.props('modelValue'),roundId:91,sourceType:'self'});await flushPromises();const fields=wrapper.findAllComponents(BusinessFields).find(v=>v.props('fields').some((f:any)=>f.key==='qty'))!;Object.assign(fields.props('model'),{qty:2,location:'实际样品柜',ownerName:'QA工程'});
  await click('确认收样');expect(wrapper.text()).toContain('网络超时');expect(wrapper.find('.test-dialog').exists()).toBe(true);await click('确认收样');expect(api.partnerSave).toHaveBeenCalledTimes(2);for(const call of api.partnerSave.mock.calls)expect(call).toMatchObject(['samples',expect.objectContaining({projectId:7,roundId:91,sourceType:'self',qty:2,requestId:'stable-receipt-request'})]);expect(wrapper.find('.test-dialog').exists()).toBe(false)
 })
 it('registers a model sample by product and phone with no category and retries the same actual receipt after failure',async()=>{
  api.partnerSave.mockRejectedValueOnce(Error('网络超时，请重试原收样')).mockResolvedValueOnce({...row(),sourceType:'model',modelProductId:701,sampleKind:undefined});await start('/model-samples');expect(wrapper.findAllComponents(ElSelect).some(s=>s.props('ariaLabel')==='筛选样品类别')).toBe(false);await click('登记收样')
  const source=wrapper.getComponent(SourceFields);expect(source.props('modelValue')).toMatchObject({sourceType:'model',sampleDomain:'model'});expect(source.props('modelValue').sampleKind).toBeUndefined();expect(source.props('modelValue').qty).toBeUndefined();expect(source.text()).not.toContain('样品类别')
  source.findAllComponents(ElSelect).find(s=>s.props('ariaLabel')==='型号样品所属产品')!.vm.$emit('change',701);await flushPromises();await source.get('.test-phone').trigger('click');await flushPromises();source.vm.$emit('update:modelValue',{...source.props('modelValue'),provenance:'工厂寄样单0185',sampleKind:'pc'});await flushPromises();Object.assign(receiptFields().props('model'),{qty:3,location:'实际型号样品柜',ownerName:'QA工程'})
  await click('确认收样');expect(wrapper.text()).toContain('网络超时');expect(wrapper.find('.test-dialog').exists()).toBe(true);await click('确认收样');expect(api.partnerSave).toHaveBeenCalledTimes(2)
  for(const call of api.partnerSave.mock.calls){expect(call).toMatchObject(['samples',expect.objectContaining({sourceType:'model',modelProductId:701,phoneModelId:185,phoneModelIds:[185],provenance:'工厂寄样单0185',qty:3,requestId:'stable-receipt-request'})]);expect(call[1]).not.toHaveProperty('sampleKind');expect(call[1]).not.toHaveProperty('projectId');expect(call[1]).not.toHaveProperty('moldSetId')}
  expect(wrapper.find('.test-dialog').exists()).toBe(false)
 })
 it('blocks incomplete model identity and shows the missing product, phone and source on the registration form',async()=>{
  await start('/model-samples');await click('登记收样');Object.assign(receiptFields().props('model'),{qty:2,location:'实际柜位',ownerName:'QA工程'});await click('确认收样');expect(api.partnerSave).not.toHaveBeenCalled();expect(wrapper.text()).toContain('请补齐：所属产品、实际手机型号、实际样品来源说明');expect(wrapper.find('.test-dialog').exists()).toBe(true)
 })
 it('requires a real batch only after explicitly linking a mold set',async()=>{
  await start('/model-samples');await click('登记收样');const source=wrapper.getComponent(SourceFields);source.vm.$emit('update:modelValue',{...source.props('modelValue'),modelProductId:701,phoneModelId:185,phoneModelIds:[185],projectId:7,moldSetId:70,provenance:'试模寄样',trialBatch:''});await flushPromises();Object.assign(receiptFields().props('model'),{qty:1,location:'QA柜位',ownerName:'QA工程'});await click('确认收样');expect(api.partnerSave).not.toHaveBeenCalled();expect(wrapper.text()).toContain('请补齐：实际试模批次／轮次');source.vm.$emit('update:modelValue',{...source.props('modelValue'),trialBatch:'T1'});await flushPromises();await click('确认收样');expect(api.partnerSave).toHaveBeenCalledWith('samples',expect.objectContaining({sourceType:'model',modelProductId:701,phoneModelId:185,projectId:7,moldSetId:70,trialBatch:'T1',qty:1}))
 })
 it('opens a model project deep link as a product model sample instead of a research round receipt',async()=>{
  await start('/model-samples?projectId=7&new=1');const source=wrapper.getComponent(SourceFields);expect(source.props('domain')).toBe('model');expect(source.props('modelValue')).toMatchObject({sourceType:'model',sampleDomain:'model',projectId:7,modelProductId:701,phoneModelId:185,phoneModelIds:[185]});expect(source.props('modelValue').qty).toBeUndefined();expect(source.props('modelValue').sampleKind).toBeUndefined();expect(api.sampleSources).not.toHaveBeenCalled();expect(api.partnerSave).not.toHaveBeenCalled()
 })
 it.each([['prototype_3d','prototype'],['mold_trial','mold'],[undefined,undefined]])('shows images and sends %s samples back to their explicit source step',async(purpose,step)=>{
  const sample:any=row('research');sample.sourceSnapshot.round.details={samplePurpose:purpose};api.partnerDetail.mockResolvedValue(sample);const router=await start('/research-samples?id=11');expect(wrapper.get('.test-files').text()).toBe('图片与附件');expect(wrapper.find('.test-qc').exists()).toBe(false);expect(wrapper.text()).toContain('查看原打样与验证');expect(wrapper.findAll('.test-files')).toHaveLength(1);expect(wrapper.text()).toContain('S1');
  await click('查看原打样与验证');expect(router.currentRoute.value.path).toBe('/product-research');expect(router.currentRoute.value.query.productId).toBe('7');expect(router.currentRoute.value.query.step).toBe(step);expect(router.currentRoute.value.query).not.toHaveProperty('stage');
 })
 it('uses confirmed selection as the primary cooperative receipt entry and keeps legacy specification intake explicit',async()=>{const router=await start('/partner-samples');expect(wrapper.text()).toContain('历史规格收样');await click('从样品选择登记');expect(router.currentRoute.value.path).toBe('/partner-selections');expect(api.partnerSave).not.toHaveBeenCalled()})
 it('retains cooperative inspections and fees on the existing sample record',async()=>{
  api.partnerDetail.mockResolvedValue(row('partner'));await start('/partner-samples?id=11');expect(wrapper.findAll('.test-qc')).toHaveLength(2);expect(api.partnerDetail).toHaveBeenCalledWith('samples',11);expect(wrapper.text()).toContain('合作选品')
 })
 it('borrows an existing sample for another real project without creating a new batch',async()=>{
  api.sampleSources.mockResolvedValue({projects:[{id:8,name:'借用用途项目',code:'QA-P8'}]});api.partnerAction.mockResolvedValue(row());await start('/model-samples?id=11');wrapper.getComponent({name:'SampleFilesStub'}).vm.$emit('update:modelValue',[{id:501,type:'file',name:'本次借样凭据',version:'V1'}]);await flushPromises();await click('借出');await wrapper.get('.test-dialog .test-object button').trigger('click');await flushPromises();await click('确认本次办理');expect(api.partnerAction).toHaveBeenCalledWith('samples',11,'loan',expect.objectContaining({projectId:8,requestId:'stable-receipt-request',refs:[{id:501,type:'file',name:'本次借样凭据',version:'V1'}]}));expect(api.partnerSave).not.toHaveBeenCalled()
 })
 it('keeps legacy receipt deep links usable and cancels leaving without discarding source input',async()=>{
  const router=await start('/samples?projectId=7&new=1');expect(api.sampleSources).toHaveBeenCalledWith();const dialog=wrapper.get('.test-dialog');await dialog.get('form').trigger('input');vi.spyOn(ElMessageBox,'confirm').mockRejectedValue('cancel');await router.push('/model-samples');await flushPromises();expect(router.currentRoute.value.path).toBe('/samples');expect(wrapper.find('.test-dialog').exists()).toBe(true);expect(api.partnerSave).not.toHaveBeenCalled()
 })
 it('recognizes a real research project in an old deep link and never sends a supplier sample category',async()=>{
  api.sampleSources.mockResolvedValue({projects:[{id:7,name:'QA项目',code:'QA-7',projectKind:'product_research'}],rounds:[],nodes:[]});await start('/samples?projectId=7&new=1');expect(wrapper.getComponent(SourceFields).props('domain')).toBe('research');await click('确认收样');expect(api.partnerSave).toHaveBeenCalledWith('samples',expect.objectContaining({sourceType:'self',sampleKind:'prototype',projectId:7}))
 })
 it('does not expose receipt actions to read-only accounts',async()=>{
  useUserStore().permissions=['partner:view'];await start('/model-samples');expect(wrapper.findAll('button').some(b=>b.text()==='登记收样')).toBe(false);expect(api.partnerSave).not.toHaveBeenCalled()
 })
 it('opens a requested original-batch action once and clears the one-time action before a later refresh',async()=>{const router=await start('/model-samples?id=11&action=loan');expect(wrapper.find('.test-dialog').exists()).toBe(true);expect(router.currentRoute.value.query.action).toBeUndefined();expect(api.partnerAction).not.toHaveBeenCalled()})

})
