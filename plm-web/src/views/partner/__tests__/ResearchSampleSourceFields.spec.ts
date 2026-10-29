import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {createMemoryHistory,createRouter} from 'vue-router'
import ElementPlus,{ElSelect} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
const api=vi.hoisted(()=>({sources:vi.fn()}))
vi.mock('@/api/modules/samples',()=>({researchSampleSources:api.sources}))
vi.mock('@/views/mold/components/SharedCompatiblePhoneSelector.vue',()=>({default:{name:'SharedPhoneStub',props:['modelValue','multiple','disabled'],emits:['update:modelValue'],template:'<button type="button" class="test-phone" :disabled="disabled" @click="$emit(\'update:modelValue\',[185])">选择手机</button>'}}))
vi.mock('@/components/PhoneObjectSelect/IdSelect.vue',()=>({default:{props:['modelValue','options','type','label','disabled'],emits:['update:modelValue'],template:'<div class="test-object" :data-type="type"><button v-for="o in options" :key="o.id" type="button" :disabled="disabled" @click="$emit(\'update:modelValue\',o.id)">{{o.name}}</button></div>'}}))
import SourceFields from '../components/ResearchSampleSourceFields.vue'
import {useUserStore} from '@/stores/user'
let wrapper:VueWrapper<any>
beforeEach(()=>{vi.resetAllMocks();setActivePinia(createPinia());useUserStore().token='qa';useUserStore().permissions=['*'];api.sources.mockImplementation(async(domain:string,id?:number,context?:{modelProductId?:number;phoneModelId?:number})=>id?{...(domain==='model'?{project:{id,name:'QA项目',code:'QA-7',modelProductId:701,phoneModels:[{phoneModelId:185,modelName:'标准手机'}]}}:{}),sets:[{id:id*10,moldSetId:id*10,itemId:1,setNo:1,name:'QA套',moldName:'明确模具'}],rounds:[{id:91,no:1,title:'实际样品',date:'2026-10-22'}]}:{products:[{id:701,name:'亮甲 2.0',salesStatus:'on_sale'},{id:702,name:'骑士'}],projects:domain==='research'||context?.modelProductId&&context.phoneModelId?[{id:7,name:'QA项目',code:'QA-7',phoneModels:[]}]:[]})})
afterEach(()=>{wrapper?.unmount();document.body.innerHTML=''})
async function start(domain:'model'|'research'='model',model:Record<string,unknown>={sourceType:'model',phoneModelIds:[],qty:9}){
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div/>'}}]});await router.push('/');wrapper=mount(SourceFields,{props:{domain,modelValue:model,'onUpdate:modelValue':(value:any)=>void wrapper.setProps({modelValue:value})},global:{plugins:[ElementPlus,router]}});await flushPromises()
}
describe('domain-specific sample source fields',()=>{
 it('chooses the product before a single standard phone through the shared selector and saves stable IDs',async()=>{
  await start();expect(wrapper.text()).not.toContain('样品类别');expect(wrapper.get('.test-phone').attributes('disabled')).toBeDefined();expect(wrapper.getComponent({name:'SharedPhoneStub'}).props('multiple')).toBe(false)
  wrapper.getComponent(ElSelect).vm.$emit('change',701);await flushPromises();expect(wrapper.get('.test-phone').attributes('disabled')).toBeUndefined();await wrapper.get('.test-phone').trigger('click');await flushPromises();expect(wrapper.props('modelValue')).toMatchObject({sourceType:'model',modelProductId:701,phoneModelId:185,phoneModelIds:[185],qty:9});expect(wrapper.props('modelValue').sampleKind).toBeUndefined();expect(api.sources).toHaveBeenCalledWith('model',undefined,{modelProductId:701,phoneModelId:185})
  await wrapper.get('.test-object[data-type="product"] button').trigger('click');await flushPromises();expect(api.sources).toHaveBeenCalledWith('model',7,{modelProductId:701,phoneModelId:185});expect(wrapper.text()).not.toContain('实际试模批次／轮次')
  await wrapper.get('.test-object[data-type="mold_set"] button').trigger('click');await flushPromises();expect(wrapper.props('modelValue')).toMatchObject({projectId:7,moldSetId:70})
  expect(wrapper.text()).toContain('实际试模批次／轮次')
 })
 it('clears stale project and set relations after switching product while keeping actual quantity and source evidence',async()=>{
  await start('model',{sourceType:'model',modelProductId:701,phoneModelId:185,phoneModelIds:[185],projectId:7,moldSetId:70,trialBatch:'T0',qty:9,provenance:'收样单0185'})
  wrapper.getComponent(ElSelect).vm.$emit('change',702);await flushPromises();expect(wrapper.props('modelValue')).toMatchObject({modelProductId:702,phoneModelId:185,qty:9,provenance:'收样单0185',trialBatch:''});expect(wrapper.props('modelValue').projectId).toBeUndefined();expect(wrapper.props('modelValue').moldSetId).toBeUndefined()
 })
 it('clears stale project and set relations after switching the actual phone without losing receipt fields',async()=>{
  await start('model',{sourceType:'model',modelProductId:701,phoneModelId:185,phoneModelIds:[185],projectId:7,moldSetId:70,trialBatch:'T0',qty:3,provenance:'工厂寄样'})
  wrapper.getComponent({name:'SharedPhoneStub'}).vm.$emit('update:modelValue',[186]);await flushPromises();expect(wrapper.props('modelValue')).toMatchObject({modelProductId:701,phoneModelId:186,phoneModelIds:[186],qty:3,provenance:'工厂寄样',trialBatch:''});expect(wrapper.props('modelValue').projectId).toBeUndefined();expect(wrapper.props('modelValue').moldSetId).toBeUndefined()
 })
 it('keeps actual research round selection separate from pending validation and does not copy planned quantities',async()=>{
  await start('research',{sampleKind:'prototype',sourceType:'self',qty:undefined});await wrapper.get('.test-object[data-type="product"] button').trigger('click');await flushPromises()
  wrapper.findAllComponents(ElSelect).find(s=>s.props('ariaLabel')==='实际研发样品轮次')!.vm.$emit('change',91);await flushPromises();expect(wrapper.props('modelValue')).toMatchObject({projectId:7,roundId:91,sourceType:'self'});expect(wrapper.props('modelValue').qty).toBeUndefined();expect(wrapper.props('modelValue').result).toBeUndefined()
 })
 it('retains source input on an API failure and supports retry',async()=>{
  api.sources.mockRejectedValueOnce(new Error('来源暂时不可用'));await start('model',{sourceType:'model',modelProductId:701,phoneModelId:185,provenance:'采购单0185',phoneModelIds:[185],qty:4});expect(wrapper.text()).toContain('来源暂时不可用');await wrapper.findAll('button').find(b=>b.text()==='重新加载来源')!.trigger('click');await flushPromises();expect(wrapper.text()).toContain('QA项目');expect(wrapper.props('modelValue')).toMatchObject({provenance:'采购单0185',modelProductId:701,phoneModelId:185,qty:4})
 })
 it('ignores a late source response after switching projects',async()=>{
  let finish!:(v:any)=>void;api.sources.mockImplementation(async(_domain:string,id?:number)=>!id?{projects:[]}:id===7?new Promise(resolve=>{finish=resolve}):{sets:[{id:80,moldSetId:80,itemId:1,setNo:2,name:'新项目套'}]});
  await start('model',{sourceType:'model',modelProductId:701,phoneModelId:185,projectId:7});await wrapper.setProps({modelValue:{sourceType:'model',modelProductId:701,phoneModelId:185,projectId:8}});await flushPromises();finish({sets:[{id:70,moldSetId:70,setNo:1,name:'过期项目套'}]});await flushPromises();expect(wrapper.text()).toContain('新项目套');expect(wrapper.text()).not.toContain('过期项目套')
 })
 it('does not restore projects from a stale phone search response',async()=>{
  let finish!:(value:any)=>void;api.sources.mockImplementation(async(_domain:string,_id?:number,context?:{phoneModelId?:number})=>context?.phoneModelId===185?new Promise(resolve=>{finish=resolve}):{products:[{id:701,name:'亮甲 2.0'}],projects:[{id:8,name:'新手机项目',code:'QA-8'}]})
  await start('model',{sourceType:'model',modelProductId:701,phoneModelId:185});await wrapper.setProps({modelValue:{sourceType:'model',modelProductId:701,phoneModelId:186}});await flushPromises();finish({projects:[{id:7,name:'旧手机项目',code:'QA-7'}]});await flushPromises();expect(wrapper.text()).toContain('新手机项目');expect(wrapper.text()).not.toContain('旧手机项目')
 })
 it('backfills only the uniquely identified product and phone from an explicit project deep link',async()=>{
  await start('model',{sourceType:'model',projectId:7,qty:undefined});expect(wrapper.props('modelValue')).toMatchObject({sourceType:'model',projectId:7,modelProductId:701,phoneModelId:185,phoneModelIds:[185]});expect(wrapper.props('modelValue').qty).toBeUndefined();expect(wrapper.props('modelValue').moldSetId).toBeUndefined()
 })
 it('leaves the actual phone unselected when a project contains several compatible models',async()=>{
  api.sources.mockImplementation(async(_domain:string,id?:number)=>id?{project:{id:7,modelProductId:701,phoneModels:[{phoneModelId:185},{phoneModelId:186}]},sets:[]}:{products:[{id:701,name:'亮甲 2.0'}],projects:[]});await start('model',{sourceType:'model',projectId:7,qty:undefined});expect(wrapper.props('modelValue').modelProductId).toBe(701);expect(wrapper.props('modelValue').phoneModelId).toBeUndefined();expect(wrapper.props('modelValue').phoneModelIds).toBeUndefined();expect(wrapper.props('modelValue').qty).toBeUndefined()
 })
 it('keeps research sample categories and clears only invalid round references when their category changes',async()=>{
  await start('research',{sampleKind:'prototype',sourceType:'self',projectId:7,roundId:91,qty:2,provenance:'实际研发寄样'});wrapper.findAllComponents(ElSelect).find(s=>s.props('ariaLabel')==='样品类别')!.vm.$emit('change','pc');await flushPromises();expect(wrapper.props('modelValue')).toMatchObject({sourceType:'self',sampleKind:'pc',projectId:7,qty:2,provenance:'实际研发寄样'});expect(wrapper.props('modelValue').roundId).toBeUndefined()
 })
 it('takes a project with no received sample to its original 3D prototype step',async()=>{
  api.sources.mockImplementation(async(_domain:string,id?:number)=>id?{rounds:[]}:{projects:[{id:7,name:'QA项目',code:'QA-7'}]});await start('research',{sampleKind:'prototype',sourceType:'self',projectId:7});const link=wrapper.findAll('a').find(a=>a.text()==='打开本项目打样记录')!;expect(link.attributes('href')).toContain('productId=7');expect(link.attributes('href')).toContain('step=prototype');expect(link.attributes('href')).not.toContain('stage=');
 })
})
