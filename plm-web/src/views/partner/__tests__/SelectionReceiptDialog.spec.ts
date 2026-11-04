import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {useUserStore} from '@/stores/user'
import {createMemoryHistory,createRouter} from 'vue-router'
import ElementPlus,{ElCheckbox,ElDatePicker,ElInput,ElInputNumber,ElSelect,ElPagination} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import ReceiptDialog from '../selection/SelectionReceiptDialog.vue'
import SampleCustodianSelect from '../samples/SampleCustodianSelect.vue'
import SampleLocationInput from '../samples/SampleLocationInput.vue'
import {newSelectionRow} from '@/utils/partnerSelections'
import type {SelectionReceiptInput,SelectionReceiptResult} from '@/types/partnerSelections'

const api=vi.hoisted(()=>({preview:vi.fn(),save:vi.fn(),candidates:vi.fn(),identities:vi.fn(),custodians:vi.fn(),locations:vi.fn()}))
vi.mock('@/api/modules/sampleManagement',()=>({sampleIdentityCandidates:api.candidates,sampleIdentities:api.identities,sampleLocationOptions:api.locations}))
vi.mock('@/api/modules/samples',()=>({getSampleCustodians:api.custodians}))
vi.mock('@/api/modules/partnerSelections',()=>({previewSelectionReceipts:api.preview,saveSelectionReceipts:api.save}))
vi.mock('@/components/PhoneModelMultiSelect/index.vue',()=>({default:{name:'PhoneModelMultiSelect',props:['modelValue','members','allowedIds','multiple','ariaLabel','disabled'],emits:['update:modelValue','cancel'],template:'<button type="button" :disabled="disabled" :aria-label="ariaLabel">选择型号</button>'}}))
vi.mock('../PartnerPhoto.vue',()=>({default:{props:['id'],template:'<span class="partner-photo">本行图片{{id}}</span>'}}))
const style=(index:number)=>({...newSelectionRow(),rowId:`row-${index}`,rowVersion:2,catalogStyleId:index,catalogStyleLabel:`XD-CP-00000${index}`,name:`款式${index}`,supplierCode:'SUP-XD',confirmed:false,phoneModelIds:[18],sampleQty:99})
const response=(input:SelectionReceiptInput)=>({id:7,version:3,canSave:!!input.date&&!!input.location&&!!input.ownerName&&input.rows.every(row=>row.phoneModelId&&(row.colorId||row.colorName?.trim())&&Number(row.qty)>0),messages:input.date&&input.location&&input.ownerName?[]:['请填写实际入库日期、位置和保管人'],rows:input.rows.map(row=>({...row,name:`款式${row.rowId.slice(-1)}`,catalogStyleLabel:'XD-CP-000001',supplierCode:'SUP-XD',sourceScopeToken:`scope-${row.rowId}`,phones:[{phoneModelId:18,modelName:'iPhone 18 Pro Max'},{phoneModelId:17,modelName:'iPhone 17 Pro'}],colors:[{id:8,name:'供方烟灰'},{id:9,name:'供方黑色'}],refs:[{id:71,name:'本款.png'}],messages:row.phoneModelId&&(row.colorId||row.colorName?.trim())&&Number(row.qty)>0?[]:['请选择实收型号、颜色和数量'],canSave:!!row.phoneModelId&&!!(row.colorId||row.colorName?.trim())&&Number(row.qty)>0}))})
const saved=(input:SelectionReceiptInput):SelectionReceiptResult=>{
 const identities=new Map<string,number>(),allocated=new Set<number>()
 return {id:7,version:4,batchCount:input.rows.length,batches:input.rows.map((row,index)=>{
  const key=JSON.stringify([row.rowId,row.phoneModelId,row.colorId||row.colorName?.trim()])
  let sampleIdentityId=identities.get(key)
  if(sampleIdentityId===undefined){sampleIdentityId=100+Number(row.rowId.slice(-1));while(allocated.has(sampleIdentityId))sampleIdentityId+=1000;identities.set(key,sampleIdentityId);allocated.add(sampleIdentityId)}
  return {clientRowId:row.clientRowId,rowId:row.rowId,id:41+index,code:`SP-00004${index+1}`,displayLabel:`XD-SP-00004${index+1}`,name:`款式${row.rowId.slice(-1)}`,phoneModelId:row.phoneModelId!,phoneName:row.phoneModelId===17?'iPhone 17 Pro':'iPhone 18 Pro Max',colorId:row.colorId||88,colorName:row.colorName?.trim()||'供方烟灰',qty:row.qty!,sampleIdentityId,sampleCode:'SY-'+String(sampleIdentityId).padStart(6,'0')}
 })}
}

let wrapper:VueWrapper
beforeEach(()=>{api.locations.mockResolvedValue({content:[],hasMore:false,size:50})})
beforeEach(()=>{vi.clearAllMocks();sessionStorage.clear();setActivePinia(createPinia());useUserStore().profile={userId:71,userName:'国内工程',roleName:'工程',department:'工程'};api.custodians.mockResolvedValue({content:[{userId:71,name:'国内工程'},{userId:72,name:'样品室保管'}]});api.candidates.mockResolvedValue({content:[],canCreate:true,requiresReview:false});api.identities.mockResolvedValue({content:[]});api.preview.mockImplementation(async(_id,input)=>response(input));api.save.mockImplementation(async(_id,input)=>saved(input))})
afterEach(()=>{wrapper?.unmount();document.body.innerHTML='';sessionStorage.clear();vi.useRealTimers()})
async function start(rows=[style(1),style(2)]){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push('/partner-selections?id=7');await router.isReady();wrapper=mount(ReceiptDialog,{props:{id:7,version:3,rows,from:'/partner-selections?id=7'},attachTo:document.body,global:{plugins:[router,ElementPlus]}});await flushPromises();return router}
const click=async(text:string)=>{const button=[...document.querySelectorAll('button')].find(button=>button.textContent?.trim()===text);expect(button,`button ${text}`).toBeDefined();button!.click();await flushPromises()}
async function fillCommon(){wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue','2026-10-27');wrapper.getComponent(SampleLocationInput).vm.$emit('update:modelValue','公司样品柜A');await flushPromises()}
const actualQuantities=()=>wrapper.findAllComponents(ElInputNumber).filter(input=>!String(input.props('ariaLabel')||'').startsWith('批量实收数量'))
const bulkColor=(index=0)=>wrapper.findAllComponents(ElInput).filter(input=>String(input.props('ariaLabel')||'').startsWith('批量实收颜色'))[index]!
const bulkQty=(index=0)=>wrapper.findAllComponents(ElInputNumber).filter(input=>String(input.props('ariaLabel')||'').startsWith('批量实收数量'))[index]!
const rowChecks=()=>wrapper.findAllComponents(ElCheckbox).filter(checkbox=>checkbox.classes().includes('receipt-row-check'))
const allCheck=()=>wrapper.findAllComponents(ElCheckbox).find(checkbox=>checkbox.props('ariaLabel')==='全选本次收样规格')!
const bulkButton=()=>[...document.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim().startsWith('应用到已勾选'))!
const allStyleModelsButton=()=>[...document.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim()==='选择各款全部型号')
const allStyleColorsButton=()=>[...document.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim()==='选择各款全部颜色')
const lastPreviewInput=()=>api.preview.mock.calls[api.preview.mock.calls.length-1][1] as SelectionReceiptInput
async function fillBulk(colorName:string,qty?:number,index=0){bulkColor(index).vm.$emit('update:modelValue',colorName);bulkQty(index).vm.$emit('update:modelValue',qty);await flushPromises()}
async function applyBulk(){expect(bulkButton()).toBeDefined();bulkButton().click();await flushPromises()}
async function checkLine(index:number,checked:boolean){rowChecks()[index]!.vm.$emit('change',checked);await flushPromises()}
const models=()=>wrapper.findAllComponents({name:'PhoneModelMultiSelect'}).filter(input=>String(input.props('ariaLabel')||'').endsWith('实收型号')&&!String(input.props('ariaLabel')||'').startsWith('批量'))
const bulkModel=()=>wrapper.findAllComponents({name:'PhoneModelMultiSelect'}).find(input=>input.props('ariaLabel')==='批量实收型号')!
const colorInputs=()=>wrapper.findAllComponents({name:'ElAutocomplete'}).filter(input=>input.element.closest('.receipt-color'))
async function chooseAllPhones(id=18){for(const model of models()){model.vm.$emit('update:modelValue',[id]);await flushPromises()}}
async function fillTwoColours(first='黑色',firstQty=2,second='白色',secondQty=2){await fillBulk(first,firstQty);await click('增加颜色');await fillBulk(second,secondQty,1)}
async function addActualLine(index:number){const row=[...document.querySelectorAll('.receipt-row')][index];[...row.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim()==='增加规格')!.click();await flushPromises()}
async function excludeActualLine(index:number){const row=[...document.querySelectorAll('.receipt-row')][index];[...row.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim()==='本次不收')!.click();await flushPromises()}
async function selectExistingColour(index:number,id:number,name:string,qty:number){colorInputs()[index].vm.$emit('select',{id,value:name,name});actualQuantities()[index].vm.$emit('update:modelValue',qty);await flushPromises()}

async function fillActual(index:number,qty=2){const modelInputs=models();modelInputs[index]!.vm.$emit('update:modelValue',[18]);colorInputs()[index]!.vm.$emit('select',{id:8,value:'供方烟灰',name:'供方烟灰',description:''});actualQuantities()[index]!.vm.$emit('update:modelValue',qty);await flushPromises()}
async function typeColor(index:number,colorName:string){const input=colorInputs()[index]!.get('input');await input.setValue(colorName);await input.trigger('blur');await flushPromises()}

describe('actual multi-style sample receipt',()=>{
 it('defaults a new receipt to today and CN and permits actual receipt without a specific storage location',async()=>{
  vi.useFakeTimers({toFake:['Date']});vi.setSystemTime(new Date('2026-11-03T10:00:00+08:00'));await start([style(1)]);
  const place=wrapper.getComponent(SampleLocationInput);expect(wrapper.getComponent(ElDatePicker).props('modelValue')).toBe('2026-11-03');expect(place.props()).toMatchObject({modelValue:'CN',site:'CN',compact:true,siteLocked:true});expect(place.props('locationId')).toBeUndefined();expect(place.props('locationCode')).toBe('');
  await fillActual(0,2);await click('保存实际收样');expect(api.save).toHaveBeenCalledTimes(1);expect(api.save.mock.calls[0][1]).toMatchObject({date:'2026-11-03',location:'CN',site:'CN',locationCode:'',rows:[expect.objectContaining({qty:2})]});
 })
 it('restores the original receipt date and selected location metadata and reconciles the exact request after a timeout',async()=>{
  api.save.mockRejectedValueOnce({code:'ECONNABORTED'});await start([style(1)]);await fillCommon();const place=wrapper.getComponent(SampleLocationInput);
  place.vm.$emit('update:site','MX');place.vm.$emit('update:modelValue','墨西哥样品间 A柜');place.vm.$emit('update:locationId',55);place.vm.$emit('update:locationCode','A001-A0001-001-001-0001');place.vm.$emit('update:locationParts',{area:'A001',rack:'A0001',shelf:'001',box:'001',sequence:'0001'});await flushPromises();await fillActual(0,2);await click('保存实际收样');
  const first=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));wrapper.unmount();vi.useFakeTimers({toFake:['Date']});vi.setSystemTime(new Date('2026-11-04T10:00:00+08:00'));await start([style(1)]);
  const restored=wrapper.getComponent(SampleLocationInput);expect(wrapper.getComponent(ElDatePicker).props('modelValue')).toBe('2026-10-27');expect(restored.props('modelValue')).toBe('墨西哥样品间 A柜');expect(restored.props('site')).toBe('MX');expect(restored.props('locationId')).toBe(55);expect(restored.props('locationCode')).toBe(first.locationCode);expect(restored.props('disabled')).toBe(true);expect(wrapper.getComponent(SampleCustodianSelect).props('disabled')).toBe(true);await click('核对本次保存结果');expect(api.save.mock.calls[1][1]).toEqual(first);
 })
 it('defaults the shared actual custodian to the logged-in account and saves a selected system account once for all receipt rows',async()=>{await start();const custodian=wrapper.getComponent(SampleCustodianSelect);expect(custodian.props('modelValue')).toBe(71);expect(custodian.props('name')).toBe('国内工程');custodian.getComponent(ElSelect).vm.$emit('update:modelValue',72);await flushPromises();await fillCommon();await fillActual(0,2);await fillActual(1,3);await click('保存实际收样');expect(api.save.mock.calls[0][1]).toMatchObject({ownerUserId:72,ownerName:'样品室保管'});expect(api.save.mock.calls[0][1].rows).toHaveLength(2)})

 it('shows adopted photos and supplier-scoped choices, with shared fields once and default two actual samples without copying the planned total',async()=>{await start();expect([...document.querySelectorAll('.receipt-common')]).toHaveLength(1);expect([...document.querySelectorAll('.receipt-row')]).toHaveLength(2);expect([...document.querySelectorAll('.partner-photo')]).toHaveLength(2);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2]);expect(wrapper.findAllComponents(ElSelect).flatMap(select=>select.findAllComponents({name:'ElOption'}).map(option=>option.props('label')))).not.toContain('全库型号');expect(document.body.textContent).not.toContain('请选择实收型号、颜色和数量');expect(api.save).not.toHaveBeenCalled();expect(api.preview.mock.calls[0][1].rows.every((row:{qty?:number})=>row.qty===2)).toBe(true)})
 it('validates missing actual data without losing the inputs or silently saving any rows',async()=>{await start();await fillCommon();await fillActual(0,3);const reads=api.preview.mock.calls.length;await click('保存实际收样');expect(document.body.textContent).toContain('还有1条规格未填写实收型号、颜色或数量');expect(api.preview).toHaveBeenCalledTimes(reads);expect(actualQuantities()[0].props('modelValue')).toBe(3);expect(actualQuantities()[1].props('modelValue')).toBe(2);expect(api.save).not.toHaveBeenCalled();expect([...document.querySelectorAll('button')].some(button=>button.textContent?.trim()==='预览本次收样')).toBe(false)})
 it('validates internally and directly saves multiple actual specifications once and links only the real returned SP batches',async()=>{const router=await start();await fillCommon();await fillActual(0,3);await fillActual(1,4);[...document.querySelectorAll('.receipt-row')][0].querySelectorAll('button').forEach(button=>{if(button.textContent?.trim()==='增加规格')button.click()});await flushPromises();expect([...document.querySelectorAll('.receipt-row')]).toHaveLength(3);expect(actualQuantities()[2].props('modelValue')).toBe(2);await fillActual(2,1);expect(document.body.textContent).toContain('合计 8 件');await click('保存实际收样');expect(api.save).toHaveBeenCalledTimes(1);const input=api.save.mock.calls[0][1];expect(input).toMatchObject({date:'2026-10-27',location:'公司样品柜A',ownerName:'国内工程'});expect(input.rows.map((row:{rowId:string;qty:number})=>[row.rowId,row.qty])).toEqual([['row-1',3],['row-2',4],['row-1',1]]);expect(input.rows.every((row:Record<string,unknown>)=>!('sampleQty'in row))).toBe(true);expect(document.body.textContent).toContain('SY-000101');expect(document.body.textContent).toContain('SY-000102');expect(wrapper.emitted('saved')).toHaveLength(1);await click('内部收样原记录');expect(router.currentRoute.value.query.id).toBe('41')})
 it('retains a failed save and reconciles timeout with the exact same request, including after dialog remount',async()=>{api.save.mockRejectedValueOnce({code:'ECONNABORTED'});await start([style(1)]);await fillCommon();await fillActual(0,6);await click('保存实际收样');const first=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));expect(document.body.textContent).toContain('保存结果待核对');expect([...document.querySelectorAll('button')].find(button=>button.textContent?.trim()==='取消')!.getAttribute('disabled')).toBeDefined();expect(sessionStorage.getItem('plm:selection-receipt:71:7')).toContain(first.requestId);wrapper.unmount();await start([style(1)]);expect(document.body.textContent).toContain('上次保存结果尚未核对');await click('核对本次保存结果');expect(api.save.mock.calls[1][1]).toEqual(first);expect(sessionStorage.getItem('plm:selection-receipt:71:7')).toBeNull();expect(document.body.textContent).toContain('实际收样已保存')})
 it('directly accepts a new colour name and saves in the same receipt without opening supplier maintenance',async()=>{await start([style(1)]);expect(document.querySelector('.receipt-bulk')).not.toBeNull();expect(rowChecks()).toHaveLength(1);await fillCommon();const model=models()[0]!;model.vm.$emit('update:modelValue',[18]);actualQuantities()[0]!.vm.$emit('update:modelValue',5);await typeColor(0,'湖蓝色');expect(colorInputs()[0].props('modelValue')).toBe('湖蓝色');expect(document.body.textContent).not.toContain('维护供方颜色');expect(document.body.textContent).not.toContain('批量录入原色');expect(api.candidates).not.toHaveBeenCalled();expect(api.save).not.toHaveBeenCalled();await click('保存实际收样');expect(api.save.mock.calls[0][1]).toMatchObject({location:'公司样品柜A',ownerName:'国内工程',rows:[expect.objectContaining({phoneModelId:18,colorName:'湖蓝色',colorId:undefined,qty:5})]});expect(document.body.textContent).toContain('湖蓝色');expect(document.body.textContent).toContain('实际收样已保存')})
 it('offers existing colours in place and discards an old colour/identity when its text changes',async()=>{api.candidates.mockResolvedValue({content:[{sampleIdentityId:71,sampleCode:'SY-000071',name:'款式1',specName:'iPhone18灰色'}],canCreate:true,requiresReview:false});await start([style(1)]);await fillCommon();await fillActual(0,3);expect(colorInputs()[0].props('modelValue')).toBe('供方烟灰');const suggest=colorInputs()[0].props('fetchSuggestions') as (query:string,done:(items:unknown[])=>void)=>void;const done=vi.fn();suggest('黑',done);expect(done).toHaveBeenCalledWith([expect.objectContaining({id:9,value:'供方黑色'})]);expect(api.candidates).toHaveBeenCalledWith(expect.objectContaining({colorId:8,phoneModelId:18}));const count=api.candidates.mock.calls.length;await typeColor(0,'珠光白');expect(api.candidates).toHaveBeenCalledTimes(count);await click('保存实际收样');expect(api.save.mock.calls[0][1].rows[0]).toMatchObject({colorName:'珠光白',colorId:undefined,sampleIdentityId:undefined,identityReason:undefined})})
 it('preserves entered colour text after changing the actual phone while clearing the former colour ID',async()=>{await start([style(1)]);await fillCommon();await fillActual(0,3);const model=models()[0]!;model.vm.$emit('update:modelValue',[17]);await flushPromises();expect(colorInputs()[0].props('modelValue')).toBe('供方烟灰');await click('保存实际收样');expect(api.save.mock.calls[0][1].rows[0]).toMatchObject({phoneModelId:17,colorName:'供方烟灰',colorId:undefined,sampleIdentityId:undefined})})
 it('retains a newly typed colour in the exact timeout replay across remounts',async()=>{api.save.mockRejectedValueOnce({code:'ECONNABORTED'});await start([style(1)]);await fillCommon();await fillActual(0,2);await typeColor(0,'冰透蓝');await click('保存实际收样');const first=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));expect(first.rows[0]).toMatchObject({colorName:'冰透蓝'});expect(first.rows[0]).not.toHaveProperty('colorId');wrapper.unmount();await start([style(1)]);expect(colorInputs()[0].props('modelValue')).toBe('冰透蓝');await click('核对本次保存结果');expect(api.save.mock.calls[1][1]).toEqual(first);expect(document.body.textContent).toContain('实际收样已保存')})
 it('shows a pending legacy colour ID without changing the saved replay payload',async()=>{const input:SelectionReceiptInput={version:3,requestId:'legacy-receipt-request',date:'2026-10-27',location:'公司样品柜A',ownerName:'国内工程',rows:[{clientRowId:'old-row',rowId:'row-1',rowVersion:2,phoneModelId:18,colorId:8,qty:2}]};sessionStorage.setItem('plm:selection-receipt:71:7',JSON.stringify({actor:'71',input,metadata:response(input).rows}));await start([style(1)]);expect(colorInputs()[0].props('modelValue')).toBe('供方烟灰');await click('核对本次保存结果');expect(api.save.mock.calls[0][1]).toEqual(input);expect(api.save.mock.calls[0][1].rows[0]).not.toHaveProperty('colorName')})
 it('keeps ambiguous existing colours distinct and lets the engineer choose one in the same field',async()=>{const choices=[{id:8,name:'黑色',code:'SC-000008',supplierColorCode:'BLACK-A',configKey:'亮面'},{id:9,name:'黑色',code:'SC-000009',supplierColorCode:'BLACK-B',configKey:'磨砂'}];api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>{const data=response(input);const ambiguous=input.rows.some(row=>row.colorName==='黑色'&&!row.colorId);return {...data,canSave:data.canSave&&!ambiguous,rows:data.rows.map(row=>({...row,colors:choices,messages:ambiguous?['有多个同名颜色，请在当前颜色框选择实际收到的颜色']:row.messages}))}});await start([style(1)]);await fillCommon();const model=models()[0]!;model.vm.$emit('update:modelValue',[18]);actualQuantities()[0]!.vm.$emit('update:modelValue',2);await typeColor(0,'黑色');await click('保存实际收样');expect(api.save).not.toHaveBeenCalled();expect(document.body.textContent).toContain('有多个同名颜色');const suggestions=vi.fn();(colorInputs()[0].props('fetchSuggestions') as (query:string,done:(items:unknown[])=>void)=>void)('黑',suggestions);expect(suggestions.mock.calls[0][0]).toEqual([expect.objectContaining({id:8,description:'色号 BLACK-A · 配置 亮面'}),expect.objectContaining({id:9,description:'色号 BLACK-B · 配置 磨砂'})]);colorInputs()[0].vm.$emit('select',suggestions.mock.calls[0][0][1]);await flushPromises();await click('保存实际收样');expect(api.save.mock.calls[0][1].rows[0]).toMatchObject({colorId:9,colorName:'黑色'})})
 it('distinguishes existing same-name records in the inline suggestions without showing codes for ordinary colours',async()=>{api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>({...response(input),rows:response(input).rows.map(row=>({...row,colors:[{id:8,name:'黑色',code:'SC-000008'},{id:9,name:'黑色',code:'SC-000009'},{id:10,name:'蓝色',code:'SC-000010'}]}))}));await start([style(1)]);const suggestions=vi.fn();(colorInputs()[0].props('fetchSuggestions') as (query:string,done:(items:unknown[])=>void)=>void)('',suggestions);expect(suggestions.mock.calls[0][0]).toEqual([expect.objectContaining({id:8,description:'记录 SC-000008'}),expect.objectContaining({id:9,description:'记录 SC-000009'}),expect.objectContaining({id:10,description:''})]);expect(api.save).not.toHaveBeenCalled()})
 it('keeps pending receipts isolated by the real logged-in user and blocks an account change during entry',async()=>{await start([style(1)]);await fillCommon();await fillActual(0,3);useUserStore().profile={userId:72,userName:'另一账号',roleName:'工程',department:'工程'};await flushPromises();expect(document.body.textContent).toContain('登录账号已改变');expect(document.querySelector('.receipt-row')).toBeNull();expect(document.querySelector('.receipt-bulk')).toBeNull();expect(allStyleModelsButton()).toBeUndefined();expect(api.save).not.toHaveBeenCalled()})

 it('updates native actual-quantity ARIA after loading and retains actual input when a save becomes ready after failure',async()=>{let resolveOptions!:(value:ReturnType<typeof response>)=>void;api.preview.mockImplementationOnce(()=>new Promise(resolve=>{resolveOptions=resolve}));api.save.mockRejectedValueOnce(new Error('暂时无法保存'));await start([style(1)]);const disabled=document.querySelector<HTMLInputElement>('.receipt-qty input')!;expect(disabled.getAttribute('aria-disabled')).toBe('true');expect(disabled.disabled).toBe(true);resolveOptions(response(api.preview.mock.calls[0][1]));await flushPromises();let ready=document.querySelector<HTMLInputElement>('.receipt-qty input')!;expect(ready.getAttribute('aria-disabled')).toBe('false');expect(ready.disabled).toBe(false);expect(ready.readOnly).toBe(false);await fillCommon();await fillActual(0,6);await click('保存实际收样');expect(document.body.textContent).toContain('暂时无法保存');ready=document.querySelector<HTMLInputElement>('.receipt-qty input')!;expect(ready.getAttribute('aria-disabled')).toBe('false');expect(ready.disabled).toBe(false);expect(ready.value).toBe('6');await click('保存实际收样');expect(api.save.mock.calls[1][1]).toEqual(api.save.mock.calls[0][1]);expect(document.body.textContent).toContain('实际收样已保存')})

 it('reuses the same true sample code across separate actual receipts and carries the supplier shipment identity',async()=>{api.candidates.mockResolvedValue({content:[{sampleIdentityId:71,sampleCode:'SY-000071',name:'款式1',specName:'iPhone18灰色'}],canCreate:true,requiresReview:false});api.save.mockImplementation(async(_id,input)=>({...saved(input),batches:saved(input).batches.map(batch=>({...batch,sampleIdentityId:71,sampleCode:'SY-000071'}))}));await start([style(1)]);await wrapper.setProps({supplierShipmentId:9});await fillCommon();await fillActual(0,3);await click('保存实际收样');expect(api.save.mock.calls[0][1]).toMatchObject({supplierShipmentId:9,rows:[expect.objectContaining({sampleIdentityId:71,qty:3})]});expect(document.body.textContent).toContain('SY-000071');wrapper.unmount();await start([style(1)]);await wrapper.setProps({supplierShipmentId:9});await fillCommon();await fillActual(0,2);await click('保存实际收样');expect(api.save.mock.calls[1][1]).toMatchObject({supplierShipmentId:9,rows:[expect.objectContaining({sampleIdentityId:71,qty:2})]});expect(api.save.mock.calls[1][1].requestId).not.toBe(api.save.mock.calls[0][1].requestId)})

})


describe('inline batch actual receipt entry',()=>{
 it('fills three checked styles locally once and retains individual edits in the original single save',async()=>{
  await start([style(1),style(2),style(3)]);await fillCommon();
  expect(rowChecks().map(input=>input.props('modelValue'))).toEqual([true,true,true]);
  expect(allCheck().props('modelValue')).toBe(true);expect(bulkButton().textContent).toContain('（3款）');
  const reads=api.preview.mock.calls.length;await fillBulk('黑色',2);await applyBulk();
  expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2,2]);
  expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['黑色','黑色','黑色']);
  expect(document.body.textContent).toContain('合计 6 件');expect(api.preview).toHaveBeenCalledTimes(reads);expect(api.save).not.toHaveBeenCalled();expect(api.candidates).not.toHaveBeenCalled();
  const modelInputs=models();
  expect(modelInputs.map(input=>input.props('modelValue'))).toEqual([[],[],[]]);
  for(const model of modelInputs){model.vm.$emit('update:modelValue',[18]);await flushPromises()}await typeColor(1,'白色');actualQuantities()[1].vm.$emit('update:modelValue',3);await flushPromises();
  expect(document.body.textContent).toContain('合计 7 件');await click('保存实际收样');
  const input=api.save.mock.calls[0][1] as SelectionReceiptInput;expect(api.save).toHaveBeenCalledTimes(1);
  expect(input.rows.map(line=>[line.rowId,line.colorName,line.qty])).toEqual([['row-1','黑色',2],['row-2','白色',3],['row-3','黑色',2]]);
  expect(new Set(input.rows.map(line=>line.clientRowId)).size).toBe(3);expect(input.rows.every(line=>line.colorId===undefined)).toBe(true);
 })
 it('changes only checked specifications and preserves an unchecked existing colour and sample identity',async()=>{
  api.candidates.mockImplementation(async(input:{selectionRowId:string})=>({content:[{sampleIdentityId:100+Number(input.selectionRowId.slice(-1)),sampleCode:'SY-000100',name:'原样品',specName:'原实收规格'}],canCreate:true,requiresReview:false}));
  await start();await fillCommon();await fillActual(0,3);await fillActual(1,4);await checkLine(1,false);
  expect(allCheck().props('indeterminate')).toBe(true);await fillBulk('冰蓝色',2);await applyBulk();await click('保存实际收样');
  const rows=api.save.mock.calls[0][1].rows;
  expect(rows[0]).toMatchObject({rowId:'row-1',phoneModelId:18,colorName:'冰蓝色',qty:2,colorId:undefined,sampleIdentityId:undefined});
  expect(rows[1]).toMatchObject({rowId:'row-2',phoneModelId:18,colorName:'供方烟灰',colorId:8,qty:4,sampleIdentityId:102});
 })
 it('preserves each suppliers own existing same-name colour and checked identity while leaving a blank style as plain text',async()=>{
  api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>({...response(input),rows:response(input).rows.map((row,index)=>({...row,supplierCode:['SUP-A','SUP-B','SUP-C'][index],colors:[{id:[8,88,188][index],name:'黑色'}]}))}));
  api.candidates.mockImplementation(async(input:{selectionRowId:string})=>({content:[{sampleIdentityId:100+Number(input.selectionRowId.slice(-1)),sampleCode:'SY-000100',name:'原样品',specName:'原实收规格'}],canCreate:true,requiresReview:false}));
  api.identities.mockResolvedValue({content:[{sampleIdentityId:999,sampleCode:'SY-000999',name:'已核对原物',specName:'同款同色'}]});
  await start([style(1),{...style(2),supplierCode:'SUP-B'},{...style(3),supplierCode:'SUP-C'}]);await fillCommon();
  const modelInputs=models();for(const model of modelInputs){model.vm.$emit('update:modelValue',[18]);await flushPromises()}
  colorInputs()[0].vm.$emit('select',{id:8,name:'黑色',value:'黑色'});colorInputs()[1].vm.$emit('select',{id:88,name:'黑色',value:'黑色'});await flushPromises();
  const identity=wrapper.findAllComponents(ElSelect).find(input=>input.props('ariaLabel')==='款式1样品码')!;
  await (identity.props('remoteMethod') as (keyword:string)=>Promise<void>)('已核对原物');identity.vm.$emit('update:modelValue',999);await flushPromises();
  wrapper.findAllComponents(ElInput).find(input=>input.props('ariaLabel')==='同物核对原因')!.vm.$emit('update:modelValue','已核实同款同型号同色');await flushPromises();
  await fillBulk(' 黑色 ',2);await applyBulk();await click('保存实际收样');
  expect(api.save.mock.calls[0][1].rows).toEqual([
   expect.objectContaining({rowId:'row-1',colorName:'黑色',colorId:8,sampleIdentityId:999,identityReason:'已核实同款同型号同色',qty:2}),
   expect.objectContaining({rowId:'row-2',colorName:'黑色',colorId:88,sampleIdentityId:102,qty:2}),
   expect.objectContaining({rowId:'row-3',colorName:'黑色',colorId:undefined,sampleIdentityId:undefined,qty:2})
  ]);
 })
 it('applies quantity alone without clearing the selected colours or their exact sample identities',async()=>{
  api.candidates.mockImplementation(async(input:{selectionRowId:string})=>({content:[{sampleIdentityId:100+Number(input.selectionRowId.slice(-1)),sampleCode:'SY-000100',name:'原样品',specName:'原实收规格'}],canCreate:true,requiresReview:false}));
  await start();await fillCommon();await fillActual(0,3);await fillActual(1,4);const candidateReads=api.candidates.mock.calls.length;
  await fillBulk('   ',7);await applyBulk();expect(api.candidates).toHaveBeenCalledTimes(candidateReads);await click('保存实际收样');
  expect(api.save.mock.calls[0][1].rows).toEqual([expect.objectContaining({rowId:'row-1',colorName:'供方烟灰',colorId:8,sampleIdentityId:101,qty:7}),expect.objectContaining({rowId:'row-2',colorName:'供方烟灰',colorId:8,sampleIdentityId:102,qty:7})]);
 })
 it('applies colour alone without replacing per-style quantities',async()=>{
  await start();await fillCommon();await fillActual(0,3);await fillActual(1,4);await fillBulk('透蓝色');await applyBulk();
  expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([3,4]);expect(document.body.textContent).toContain('合计 7 件');await click('保存实际收样');
  expect(api.save.mock.calls[0][1].rows.map((row:{colorName:string;qty:number})=>[row.colorName,row.qty])).toEqual([['透蓝色',3],['透蓝色',4]]);
 })
 it('cannot fill empty input, invalid quantities or an empty selection',async()=>{
  await start();const reads=api.preview.mock.calls.length;await applyBulk();expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2]);
  await fillBulk('黑色',2);allCheck().vm.$emit('change',false);await flushPromises();expect(bulkButton().disabled).toBe(true);expect(allStyleModelsButton()?.disabled).toBe(true);allStyleModelsButton()!.click();await applyBulk();
  expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2]);
  allCheck().vm.$emit('change',true);await flushPromises();
  for(const invalid of [0,-1,1.5,Number.MAX_SAFE_INTEGER+1]){bulkQty().vm.$emit('update:modelValue',invalid);bulkButton().click();await flushPromises();expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2])}
  expect(api.preview).toHaveBeenCalledTimes(reads);expect(api.save).not.toHaveBeenCalled();
 })
 it('leaves added specifications unchecked until explicitly selected and removes their selection when excluded',async()=>{
  await start();const first=document.querySelector('.receipt-row')!;[...first.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim()==='增加规格')!.click();await flushPromises();
  expect(rowChecks().map(input=>input.props('modelValue'))).toEqual([true,true,false]);expect(allCheck().props('indeterminate')).toBe(true);
  await fillBulk('黑色',2);await applyBulk();expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2,2]);
  expect(colorInputs()[2].props('modelValue')).toBe('');
  await checkLine(2,true);const extra=[...document.querySelectorAll('.receipt-row')][2];[...extra.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim()==='本次不收')!.click();await flushPromises();
  expect(rowChecks()).toHaveLength(2);expect(allCheck().props('modelValue')).toBe(true);expect(bulkButton().textContent).toContain('（2款）');
 })
 it('keeps batch controls read-only during loading and saving and restores their native quantity input',async()=>{
  let resolveOptions!:(value:ReturnType<typeof response>)=>void;api.preview.mockImplementationOnce(()=>new Promise(resolve=>{resolveOptions=resolve}));await start();
  const input=bulkQty().get('input');expect(input.attributes('aria-disabled')).toBe('true');expect((input.element as HTMLInputElement).disabled).toBe(true);expect(bulkColor().props('disabled')).toBe(true);expect(rowChecks().every(input=>input.props('disabled'))).toBe(true);expect(bulkButton().disabled).toBe(true);expect(allStyleModelsButton()?.disabled).toBe(true);
  resolveOptions(response(api.preview.mock.calls[0][1]));await flushPromises();const ready=bulkQty().get('input');expect(ready.attributes('aria-disabled')).toBe('false');expect((ready.element as HTMLInputElement).disabled).toBe(false);expect(bulkColor().props('disabled')).toBe(false);expect(allStyleModelsButton()?.disabled).toBe(false);
  await fillCommon();await fillActual(0,3);await fillActual(1,4);await fillBulk('黑色',2);await applyBulk();
  let resolveSave!:(value:SelectionReceiptResult)=>void;api.save.mockImplementationOnce(()=>new Promise(resolve=>{resolveSave=resolve}));await click('保存实际收样');
  const submitted=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));expect(bulkColor().props('disabled')).toBe(true);expect(bulkQty().get('input').attributes('aria-disabled')).toBe('true');expect(bulkButton().disabled).toBe(true);expect(allStyleModelsButton()?.disabled).toBe(true);allStyleModelsButton()!.click();
  bulkColor().vm.$emit('update:modelValue','不可覆盖');bulkQty().vm.$emit('update:modelValue',9);bulkButton().click();await flushPromises();
  expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2]);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['黑色','黑色']);expect(api.save).toHaveBeenCalledTimes(1);
  resolveSave(saved(submitted));await flushPromises();expect(document.body.textContent).toContain('实际收样已保存');
 })
 it('replays an unknown multi-style save exactly even if batch widgets receive stale change events',async()=>{
  api.save.mockRejectedValueOnce({code:'ECONNABORTED'});await start();await fillCommon();await fillActual(0,3);await fillActual(1,4);await fillBulk('黑色',2);await applyBulk();await click('保存实际收样');
  const first=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));wrapper.unmount();await start();expect(document.body.textContent).toContain('上次保存结果尚未核对');
  expect(bulkColor().props('disabled')).toBe(true);expect(bulkQty().props('disabled')).toBe(true);expect(bulkButton().disabled).toBe(true);expect(allStyleModelsButton()?.disabled).toBe(true);allStyleModelsButton()!.click();
  bulkColor().vm.$emit('update:modelValue','不可覆盖');bulkQty().vm.$emit('update:modelValue',9);bulkButton().click();await flushPromises();
  expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['黑色','黑色']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2]);
  await click('核对本次保存结果');expect(api.save.mock.calls[1][1]).toEqual(first);
 })
 it('discards a late identity response for a colour replaced through batch entry',async()=>{
  let resolveIdentity!:(value:unknown)=>void;api.candidates.mockImplementationOnce(()=>new Promise(resolve=>{resolveIdentity=resolve}));await start();await fillCommon();await fillActual(0,3);await fillActual(1,4);await checkLine(1,false);
  await fillBulk('新颜色',2);await applyBulk();resolveIdentity({content:[{sampleIdentityId:999,sampleCode:'SY-000999',name:'旧颜色',specName:'旧规格'}],canCreate:true,requiresReview:false});await flushPromises();await click('保存实际收样');
  expect(api.save.mock.calls[0][1].rows[0]).toMatchObject({colorName:'新颜色',colorId:undefined,sampleIdentityId:undefined,qty:2});expect(api.save.mock.calls[0][1].rows[1]).toMatchObject({colorName:'供方烟灰',colorId:8,qty:4});
 })
})


describe('multiple shared colour specifications',()=>{
 it('expands three styles to six adjacent specifications once and keeps their IDs stable on repeated application',async()=>{
  await start([style(1),style(2),style(3)]);await fillCommon();await chooseAllPhones();await fillTwoColours();await click('增加颜色');
  const reads=api.preview.mock.calls.length;await applyBulk();
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(6);expect(document.body.textContent).toContain('合计 12 件');
  expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['黑色','白色','黑色','白色','黑色','白色']);
  expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2,2,2,2,2]);expect(rowChecks().every(input=>input.props('modelValue'))).toBe(true);
  const initialRows=[...document.querySelectorAll('.receipt-row')];const removeBlank=document.querySelector<HTMLButtonElement>('button[aria-label="移除第3条批量颜色"]');expect(removeBlank).not.toBeNull();removeBlank!.click();await flushPromises();
  await applyBulk();expect([...document.querySelectorAll('.receipt-row')]).toEqual(initialRows);expect(api.preview).toHaveBeenCalledTimes(reads);expect(api.save).not.toHaveBeenCalled();expect(api.candidates).not.toHaveBeenCalled();
  actualQuantities()[1].vm.$emit('update:modelValue',3);await flushPromises();expect(document.body.textContent).toContain('合计 13 件');await click('保存实际收样');
  const input=api.save.mock.calls[0][1] as SelectionReceiptInput;expect(api.save).toHaveBeenCalledTimes(1);expect(new Set(input.rows.map(row=>row.clientRowId)).size).toBe(6);
  expect(input.rows.map(row=>[row.rowId,row.phoneModelId,row.colorName,row.qty])).toEqual([['row-1',18,'黑色',2],['row-1',18,'白色',3],['row-2',18,'黑色',2],['row-2',18,'白色',2],['row-3',18,'黑色',2],['row-3',18,'白色',2]]);
  expect(input.rows.every(row=>row.colorId===undefined&&row.sampleIdentityId===undefined)).toBe(true);expect(document.querySelectorAll('.receipt-results article')).toHaveLength(6);expect(wrapper.getComponent({name:'SampleLabelPrint'}).props('samples')).toHaveLength(6);
 })
 it('keeps an unchecked style and unrelated existing colours while filling selected styles',async()=>{
  api.candidates.mockImplementation(async(input:{selectionRowId:string})=>({content:[{sampleIdentityId:100+Number(input.selectionRowId.slice(-1)),sampleCode:'SY-000100',name:'原样品',specName:'原实收规格'}],canCreate:true,requiresReview:false}));
  await start([style(1),style(2),style(3)]);await fillCommon();await chooseAllPhones();await selectExistingColour(0,33,'绿色',5);await fillActual(2,7);await checkLine(2,false);await fillTwoColours();await applyBulk();
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(6);expect(document.body.textContent).toContain('合计 20 件');await click('保存实际收样');const input=api.save.mock.calls[0][1] as SelectionReceiptInput;
  expect(input.rows.filter(row=>row.rowId==='row-1')).toEqual([expect.objectContaining({colorId:33,colorName:'绿色',sampleIdentityId:101,qty:5}),expect.objectContaining({colorName:'黑色',colorId:undefined,qty:2}),expect.objectContaining({colorName:'白色',colorId:undefined,qty:2})]);
  expect(input.rows.filter(row=>row.rowId==='row-2').map(row=>[row.colorName,row.qty])).toEqual([['黑色',2],['白色',2]]);
  expect(input.rows.filter(row=>row.rowId==='row-3')).toEqual([expect.objectContaining({colorId:8,colorName:'供方烟灰',sampleIdentityId:103,qty:7})]);
 })
 it('reserves later same-name matches before creating earlier colours and retains each suppliers own chosen ID',async()=>{
  api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>({...response(input),rows:response(input).rows.map(row=>({...row,supplierCode:row.rowId==='row-1'?'SUP-A':'SUP-B',colors:[{id:row.rowId==='row-1'?8:88,name:'黑色'}]}))}));
  api.candidates.mockImplementation(async(input:{selectionRowId:string})=>({content:[{sampleIdentityId:100+Number(input.selectionRowId.slice(-1)),sampleCode:'SY-000100',name:'原样品',specName:'原实收规格'}],canCreate:true,requiresReview:false}));
  await start([style(1),{...style(2),supplierCode:'SUP-B'}]);await fillCommon();await chooseAllPhones();await selectExistingColour(0,8,'黑色',1);await selectExistingColour(1,88,'黑色',1);
  await fillTwoColours('白色',2,'黑色',3);await applyBulk();await click('保存实际收样');const input=api.save.mock.calls[0][1] as SelectionReceiptInput;
  expect(input.rows.filter(row=>row.rowId==='row-1')).toEqual([expect.objectContaining({colorId:8,colorName:'黑色',sampleIdentityId:101,qty:3}),expect.objectContaining({colorName:'白色',colorId:undefined,sampleIdentityId:undefined,qty:2})]);
  expect(input.rows.filter(row=>row.rowId==='row-2')).toEqual([expect.objectContaining({colorId:88,colorName:'黑色',sampleIdentityId:102,qty:3}),expect.objectContaining({colorName:'白色',colorId:undefined,sampleIdentityId:undefined,qty:2})]);
 })
 it('keeps two explicitly selected phone scopes of the same source style separate',async()=>{
  await start();await fillCommon();await chooseAllPhones();await addActualLine(0);models()[2].vm.$emit('update:modelValue',[17]);await flushPromises();await checkLine(1,false);await checkLine(2,true);await fillTwoColours();await applyBulk();
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(5);const untouchedIndex=[...document.querySelectorAll('.receipt-row')].findIndex(row=>row.textContent?.includes('款式2'));expect(untouchedIndex).toBeGreaterThanOrEqual(0);expect(actualQuantities()[untouchedIndex].props('modelValue')).toBe(2);
  await excludeActualLine(untouchedIndex);await click('保存实际收样');
  const input=api.save.mock.calls[0][1] as SelectionReceiptInput;expect(input.rows.every(row=>row.rowId==='row-1')).toBe(true);expect(new Set(input.rows.map(row=>row.clientRowId)).size).toBe(4);
  expect(input.rows.map(row=>[row.phoneModelId,row.colorName,row.qty])).toEqual([[18,'黑色',2],[18,'白色',2],[17,'黑色',2],[17,'白色',2]]);
 })
 it('atomically refuses a colour already present in an unchecked manual specification',async()=>{
  await start();await chooseAllPhones();await selectExistingColour(1,8,'黑色',3);await addActualLine(1);await selectExistingColour(2,9,'白色',9);await fillTwoColours();
  const colors=colorInputs().map(input=>input.props('modelValue')),quantities=actualQuantities().map(input=>input.props('modelValue'));const reads=api.preview.mock.calls.length;await applyBulk();
  expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(colors);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual(quantities);expect(document.querySelectorAll('.receipt-row')).toHaveLength(3);expect(document.body.textContent).toContain('未勾选的同色规格');expect(api.preview).toHaveBeenCalledTimes(reads);expect(api.save).not.toHaveBeenCalled();
 })
 it('atomically refuses multiple checked same-name source records instead of choosing an identity',async()=>{
  await start();await chooseAllPhones();await selectExistingColour(1,8,'黑色',3);await addActualLine(1);await selectExistingColour(2,9,' 黑色 ',9);await checkLine(2,true);await fillTwoColours();
  const colors=colorInputs().map(input=>input.props('modelValue')),quantities=actualQuantities().map(input=>input.props('modelValue'));await applyBulk();
  expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(colors);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual(quantities);expect(document.querySelectorAll('.receipt-row')).toHaveLength(3);expect(document.body.textContent).toContain('多条同名颜色');expect(api.save).not.toHaveBeenCalled();
 })
 it('validates all templates before any changes, including folded duplicates and incomplete or invalid extra rows',async()=>{
  await start();await chooseAllPhones();await fillTwoColours('Black',2,' black ',2);const reads=api.preview.mock.calls.length;await applyBulk();expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['','']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2]);expect(document.body.textContent).toContain('颜色重复');
  await fillBulk('',2,1);await applyBulk();expect(document.querySelectorAll('.receipt-row')).toHaveLength(2);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['','']);expect(document.body.textContent).toContain('逐行填写颜色名称');
  await fillBulk('White',2,1);bulkQty(1).vm.$emit('update:modelValue',0);bulkButton().click();await flushPromises();
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(2);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2]);expect(api.preview).toHaveBeenCalledTimes(reads);expect(api.save).not.toHaveBeenCalled();
 })
 it('requires each selected phone explicitly for multiple colours and recovers without guessing one',async()=>{
  await start();models()[0].vm.$emit('update:modelValue',[18]);await flushPromises();await fillTwoColours();await applyBulk();
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(2);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['','']);expect(models().map(model=>model.props('modelValue'))).toEqual([[18],[]]);expect(document.body.textContent).toContain('实收型号');
  models()[1].vm.$emit('update:modelValue',[17]);await flushPromises();await applyBulk();expect(document.querySelectorAll('.receipt-row')).toHaveLength(4);expect(models().map(model=>model.props('modelValue'))).toEqual([[18],[18],[17],[17]]);expect(api.save).not.toHaveBeenCalled();
 })
 it('cancels a filled multi-colour local form without saving or carrying generated rows into the next receipt',async()=>{
  await start([style(1),style(2),style(3)]);await chooseAllPhones();await fillTwoColours();await applyBulk();expect(document.querySelectorAll('.receipt-row')).toHaveLength(6);await click('取消');
  expect(wrapper.emitted('close')).toHaveLength(1);expect(api.save).not.toHaveBeenCalled();expect(sessionStorage.getItem('plm:selection-receipt:71:7')).toBeNull();wrapper.unmount();await start([style(1),style(2),style(3)]);
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(3);expect(document.querySelectorAll('.receipt-bulk-line')).toHaveLength(1);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['','','']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2,2]);
 })
 it('locks and exactly replays a pending six-specification save without generating extra rows',async()=>{
  api.save.mockRejectedValueOnce({code:'ECONNABORTED'});await start([style(1),style(2),style(3)]);await fillCommon();await chooseAllPhones();await fillTwoColours();await applyBulk();await click('保存实际收样');
  const first=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));expect(first.rows).toHaveLength(6);wrapper.unmount();await start([style(1),style(2),style(3)]);expect(document.querySelectorAll('.receipt-row')).toHaveLength(6);
  expect(bulkColor().props('disabled')).toBe(true);expect(bulkQty().props('disabled')).toBe(true);const add=[...document.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim()==='增加颜色')!;expect(add.disabled).toBe(true);add.click();bulkColor().vm.$emit('update:modelValue','不可覆盖');bulkQty().vm.$emit('update:modelValue',9);bulkButton().click();await flushPromises();
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(6);expect(document.querySelectorAll('.receipt-bulk-line')).toHaveLength(1);const reads=api.preview.mock.calls.length;await click('核对本次保存结果');expect(api.save.mock.calls[1][1]).toEqual(first);expect(api.preview).toHaveBeenCalledTimes(reads);expect(sessionStorage.getItem('plm:selection-receipt:71:7')).toBeNull();
 })
})


describe('shared batch actual phone models',()=>{
 it('expands one style into explicitly selected models and colours once through the original save',async()=>{
  await start([style(1)]);await fillCommon();expect(bulkModel().props('allowedIds')).toEqual([18,17]);expect(bulkModel().props('modelValue')).toEqual([]);expect(models()[0].props('multiple')).toBe(false);expect(models()[0].props('allowedIds')).toEqual([18,17]);
  bulkModel().vm.$emit('update:modelValue',[18,17]);await fillTwoColours();await applyBulk();
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(4);expect(models().map(input=>input.props('modelValue'))).toEqual([[18],[18],[17],[17]]);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2,2,2]);expect(document.body.textContent).toContain('合计 8 件');expect(document.querySelectorAll('.partner-photo')).toHaveLength(4);
  const elements=[...document.querySelectorAll('.receipt-row')],reads=api.preview.mock.calls.length;await applyBulk();expect([...document.querySelectorAll('.receipt-row')]).toEqual(elements);expect(api.preview).toHaveBeenCalledTimes(reads);
  await click('保存实际收样');const input=api.save.mock.calls[0][1] as SelectionReceiptInput;expect(new Set(input.rows.map(row=>row.clientRowId)).size).toBe(4);expect(input.rows.map(row=>[row.phoneModelId,row.colorName,row.qty])).toEqual([[18,'黑色',2],[18,'白色',2],[17,'黑色',2],[17,'白色',2]]);
 })
 it('applies each styles supported phone intersection and retains entered exceptions without duplicating existing specifications',async()=>{
  api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>({...response(input),rows:response(input).rows.map(row=>({...row,phones:row.rowId==='row-2'?[{phoneModelId:18,modelName:'iPhone 18 Pro Max'}]:row.phones}))}));
  await start();await fillCommon();await fillActual(0,3);await fillActual(1,4);await typeColor(1,'手填蓝');bulkModel().vm.$emit('update:modelValue',[18,17]);await fillBulk('黑色',2);
  await checkLine(1,false);await applyBulk();expect(document.querySelectorAll('.receipt-row')).toHaveLength(4);const untouched=[...document.querySelectorAll('.receipt-row')].findIndex(row=>row.textContent?.includes('款式2'));expect(colorInputs()[untouched].props('modelValue')).toBe('手填蓝');expect(actualQuantities()[untouched].props('modelValue')).toBe(4);
  await checkLine(untouched,true);await applyBulk();expect(document.querySelectorAll('.receipt-row')).toHaveLength(5);expect(document.body.textContent).toContain('款式2');expect(document.body.textContent).toContain('iPhone 17 Pro');expect(models().map(input=>input.props('modelValue'))).toEqual([[18],[18],[17],[18],[18]]);expect(api.save).not.toHaveBeenCalled();
 })
 it('applies model choices alone without multiplying entered quantities or deleting other specifications',async()=>{
  await start([style(1)]);await fillCommon();await typeColor(0,'手填绿色');actualQuantities()[0].vm.$emit('update:modelValue',3);bulkModel().vm.$emit('update:modelValue',[18,17]);await flushPromises();await applyBulk();
  expect(models().map(input=>input.props('modelValue'))).toEqual([[18],[17]]);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['手填绿色','手填绿色']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([3,3]);expect(document.body.textContent).toContain('合计 6 件');const rows=[...document.querySelectorAll('.receipt-row')];await applyBulk();expect([...document.querySelectorAll('.receipt-row')]).toEqual(rows);expect(api.save).not.toHaveBeenCalled();
 })
 it('preserves a matched existing colour and identity while newly selected models get their own specification',async()=>{
  api.candidates.mockResolvedValue({content:[{sampleIdentityId:101,sampleCode:'SY-000101',name:'款式1',specName:'原同款同型号同色'}],canCreate:true,requiresReview:false});await start([style(1)]);await fillCommon();await chooseAllPhones();await selectExistingColour(0,8,'黑色',3);bulkModel().vm.$emit('update:modelValue',[18,17]);await fillBulk('黑色',7);await applyBulk();await click('保存实际收样');
  expect(api.save.mock.calls[0][1].rows).toEqual([expect.objectContaining({phoneModelId:18,colorId:8,colorName:'黑色',sampleIdentityId:101,qty:7}),expect.objectContaining({phoneModelId:17,colorId:undefined,colorName:'黑色',sampleIdentityId:undefined,qty:7})]);
 })
 it('preserves an unchecked same-model colour exception while adding only missing phone specifications',async()=>{
  await start();await chooseAllPhones();await selectExistingColour(0,8,'黑色',3);await addActualLine(0);await typeColor(2,'黑色');actualQuantities()[2].vm.$emit('update:modelValue',5);bulkModel().vm.$emit('update:modelValue',[18,17]);await fillBulk('黑色',2);const rows=[...document.querySelectorAll('.receipt-row')],quantities=actualQuantities().map(input=>input.props('modelValue')),reads=api.preview.mock.calls.length;await applyBulk();
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(5);const untouched=[...document.querySelectorAll('.receipt-row')].indexOf(rows[2]);expect(untouched).toBeGreaterThanOrEqual(0);expect(actualQuantities()[untouched].props('modelValue')).toBe(quantities[2]);expect(colorInputs()[untouched].props('modelValue')).toBe('黑色');const result=lastPreviewInput().rows;expect(result.filter(row=>row.rowId==='row-1'&&row.phoneModelId===18)).toHaveLength(2);expect(result.filter(row=>row.rowId==='row-1'&&row.phoneModelId===17)).toHaveLength(1);expect(api.preview).toHaveBeenCalledTimes(reads+1);expect(api.save).not.toHaveBeenCalled();
 })
 it('freezes a multi-model unknown save and replays exactly the same rows and request after reopening',async()=>{
  api.save.mockRejectedValueOnce({code:'ECONNABORTED'});await start([style(1)]);await fillCommon();bulkModel().vm.$emit('update:modelValue',[18,17]);await fillBulk('黑色',2);await applyBulk();await click('保存实际收样');const first=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));expect(first.rows).toHaveLength(2);expect(bulkModel().props('disabled')).toBe(true);wrapper.unmount();await start([style(1)]);expect(bulkModel().props('disabled')).toBe(true);bulkModel().vm.$emit('update:modelValue',[17]);await flushPromises();await applyBulk();expect(models().map(input=>input.props('modelValue'))).toEqual([[18],[17]]);await click('核对本次保存结果');expect(api.save.mock.calls[1][1]).toEqual(first);
 })
})


// Only the API boundary supplies each style's saved phone scope; the component owns expansion.
function previewStylePhones(scopes:Record<string,number[]>,sampleColors:Record<string,string[]>={}){
 api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>({...response(input),rows:response(input).rows.map(row=>({...row,phones:(scopes[row.rowId]||[]).map(phoneModelId=>({phoneModelId,modelName:`型号${phoneModelId}`})),sampleColors:sampleColors[row.rowId]}))}))
}

describe('select every saved phone per checked style',()=>{
 it('expands A 1/2/3 and B 1/2 independently without cross-style warnings or repeated rows',async()=>{
  previewStylePhones({'row-1':[1,2,3,3],'row-2':[1,2]});
  await start([{...style(1),phoneModelIds:[1,2,3]},{...style(2),phoneModelIds:[1,2]}]);
  await click('选择各款全部型号');
  expect(models().map(input=>input.props('modelValue'))).toEqual([[1],[2],[3],[1],[2]]);
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(5);expect(rowChecks().every(input=>input.props('modelValue'))).toBe(true);
  expect(actualQuantities().every(input=>input.props('modelValue')===2)).toBe(true);
  expect(document.body.textContent).not.toContain('部分款式不包含');expect(document.querySelector('.receipt-bulk-issues')).toBeNull();
  const input=lastPreviewInput(),rows=[...document.querySelectorAll('.receipt-row')],reads=api.preview.mock.calls.length;
  expect(input.rows.map(row=>[row.rowId,row.phoneModelId,row.rowVersion])).toEqual([['row-1',1,2],['row-1',2,2],['row-1',3,2],['row-2',1,2],['row-2',2,2]]);
  expect(new Set(input.rows.map(row=>row.clientRowId)).size).toBe(5);expect(input.rows.every(row=>row.sampleIdentityId===undefined)).toBe(true);
  await click('选择各款全部型号');expect([...document.querySelectorAll('.receipt-row')]).toEqual(rows);expect(api.preview).toHaveBeenCalledTimes(reads);expect(api.save).not.toHaveBeenCalled();
 })
 it('replaces an incompatible shared model choice while preserving batch colour and quantity for the original single save',async()=>{
  previewStylePhones({'row-1':[18,17,16],'row-2':[18,17]});
  await start([{...style(1),phoneModelIds:[18,17,16]},{...style(2),phoneModelIds:[18,17]}]);await fillCommon();
  bulkModel().vm.$emit('update:modelValue',[18,17,16]);await fillBulk('紫色',7);
  await click('选择各款全部型号');
  expect(bulkModel().props('modelValue')).toEqual([]);expect(document.querySelector('.receipt-bulk-issues')).toBeNull();
  expect(bulkColor().props('modelValue')).toBe('紫色');expect(bulkQty().props('modelValue')).toBe(7);
  expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['','','','','']);expect(actualQuantities().every(input=>input.props('modelValue')===2)).toBe(true);
  await applyBulk();await click('保存实际收样');expect(api.save).toHaveBeenCalledTimes(1);
  const input=api.save.mock.calls[0][1] as SelectionReceiptInput;expect(input.version).toBe(3);
  expect(input.rows.map(row=>[row.rowId,row.phoneModelId,row.colorName,row.qty])).toEqual([['row-1',18,'紫色',7],['row-1',17,'紫色',7],['row-1',16,'紫色',7],['row-2',18,'紫色',7],['row-2',17,'紫色',7]]);
  expect(input.rows.every(row=>row.rowVersion===2)).toBe(true);expect(new Set(input.rows.map(row=>row.clientRowId)).size).toBe(5);
 })
 it('retains existing same-phone colours, quantities and identities while adding only missing phone rows',async()=>{
  previewStylePhones({'row-1':[18,17,16]});
  api.candidates.mockImplementation(async(input:{colorId:number})=>({content:[{sampleIdentityId:500+input.colorId,sampleCode:'SY-000500',name:'原同物样品',specName:'原型号原颜色'}],canCreate:true,requiresReview:false}));
  await start([{...style(1),phoneModelIds:[18,17,16]}]);await fillCommon();await fillActual(0,2);await addActualLine(0);await selectExistingColour(1,9,'白色',3);
  const existing=[...document.querySelectorAll('.receipt-row')],firstId=(api.preview.mock.calls[0][1] as SelectionReceiptInput).rows[0].clientRowId;
  await click('选择各款全部型号');
  expect([...document.querySelectorAll('.receipt-row')].slice(0,2)).toEqual(existing);expect(models().map(input=>input.props('modelValue'))).toEqual([[18],[18],[17],[16]]);
  expect(rowChecks().map(input=>input.props('modelValue'))).toEqual([true,false,true,true]);
  const expanded=(lastPreviewInput()).rows;
  expect(expanded.slice(0,2)).toEqual([expect.objectContaining({clientRowId:firstId,phoneModelId:18,colorId:8,colorName:'供方烟灰',sampleIdentityId:508,qty:2}),expect.objectContaining({phoneModelId:18,colorId:9,colorName:'白色',sampleIdentityId:509,qty:3})]);
  expect(expanded.slice(2).every(row=>row.qty===2&&row.colorId===undefined&&row.sampleIdentityId===undefined&&row.identityReason===undefined)).toBe(true);
  await typeColor(2,'新蓝');actualQuantities()[2].vm.$emit('update:modelValue',1);await typeColor(3,'新白');actualQuantities()[3].vm.$emit('update:modelValue',1);await flushPromises();await click('保存实际收样');
  const savedRows=(api.save.mock.calls[0][1] as SelectionReceiptInput).rows;expect(savedRows.slice(0,2)).toEqual(expanded.slice(0,2));expect(api.save).toHaveBeenCalledTimes(1);
 })
 it('expands only checked styles and leaves an unchecked style and its original client identity unchanged',async()=>{
  previewStylePhones({'row-1':[1,2,3],'row-2':[1,2]});
  await start([{...style(1),phoneModelIds:[1,2,3]},{...style(2),phoneModelIds:[1,2]}]);
  const untouched=JSON.parse(JSON.stringify((api.preview.mock.calls[0][1] as SelectionReceiptInput).rows[1]));await checkLine(1,false);await click('选择各款全部型号');
  expect(models().map(input=>input.props('modelValue'))).toEqual([[1],[2],[3],[]]);
  expect(rowChecks().map(input=>input.props('modelValue'))).toEqual([true,true,true,false]);
  expect((lastPreviewInput()).rows.find(row=>row.rowId==='row-2')).toEqual({...untouched,sourceScopeToken:'scope-row-2'});expect(api.save).not.toHaveBeenCalled();
 })
 it.each(['empty','disabled'] as const)('skips an unavailable placeholder while expanding another checked styles own phones: %s',async kind=>{
  api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>({...response(input),rows:response(input).rows.map(row=>({...row,phones:row.rowId==='row-1'?[{phoneModelId:18,modelName:'iPhone 18'}]:kind==='empty'?[]:[{phoneModelId:19,modelName:'不可用旧型号',status:'disabled'}]}))}));
  await start();const first=document.querySelectorAll('.receipt-row')[0];
  await click('选择各款全部型号');expect(document.querySelectorAll('.receipt-row')).toHaveLength(1);expect(document.querySelectorAll('.receipt-row')[0]).toBe(first);expect(models().map(input=>input.props('modelValue'))).toEqual([[18]]);expect(document.body.textContent).toContain('本次跳过');expect(document.body.textContent).toContain('款式2');expect(api.save).not.toHaveBeenCalled();
 })
 it('keeps all 585 per-style models in one original payload while paging the real entry controls',async()=>{
  const ids=Array.from({length:39},(_,i)=>i+1),rows=Array.from({length:15},(_,i)=>({...style(i+1),phoneModelIds:ids}));
  previewStylePhones(Object.fromEntries(rows.map(row=>[row.rowId,ids])));await start(rows);await fillCommon();
  await click('选择各款全部型号');const expanded=lastPreviewInput().rows;
  expect(expanded).toHaveLength(585);expect(new Set(expanded.map(row=>row.clientRowId)).size).toBe(585);
  for(const row of rows)expect(expanded.filter(line=>line.rowId===row.rowId).map(line=>line.phoneModelId)).toEqual(ids);
  expect(expanded.every(row=>row.qty===2&&row.colorId===undefined&&row.sampleIdentityId===undefined)).toBe(true);
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(50);const pager=wrapper.getComponent(ElPagination);
  expect(pager.props()).toMatchObject({total:585,pageSize:50,currentPage:1,disabled:false});
  actualQuantities()[0].vm.$emit('update:modelValue',2);pager.vm.$emit('update:current-page',12);await flushPromises();
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(35);expect(models().map(input=>input.props('modelValue'))).toEqual(ids.slice(4).map(id=>[id]));
  pager.vm.$emit('update:current-page',1);await flushPromises();expect(document.querySelectorAll('.receipt-row')).toHaveLength(50);expect(actualQuantities()[0].props('modelValue')).toBe(2);
  await fillBulk('黑色',1);await applyBulk();let resolveSave!:(value:SelectionReceiptResult)=>void;api.save.mockImplementationOnce(()=>new Promise(resolve=>{resolveSave=resolve}));await click('保存实际收样');
  expect(api.save).toHaveBeenCalledTimes(1);expect(pager.props('disabled')).toBe(true);const input=api.save.mock.calls[0][1] as SelectionReceiptInput;
  expect(input.rows).toHaveLength(585);expect(new Set(input.rows.map(row=>row.clientRowId)).size).toBe(585);expect(input.rows.every(row=>row.rowVersion===2&&row.colorName==='黑色'&&row.qty===1)).toBe(true);
  resolveSave(saved(input));await flushPromises();expect(document.body.textContent).toContain('实际收样已保存');
 },60000)
})


describe('actual receipt page navigation',()=>{
 it('opens an added specification on its own page and clamps back after the last row is removed',async()=>{
  await start(Array.from({length:50},(_,i)=>style(i+1)));actualQuantities()[0].vm.$emit('update:modelValue',7);await flushPromises();
  expect(wrapper.findComponent(ElPagination).exists()).toBe(false);await addActualLine(0);
  expect(wrapper.getComponent(ElPagination).props()).toMatchObject({currentPage:2,total:51,pageSize:50});expect(document.querySelectorAll('.receipt-row')).toHaveLength(1);
  expect(actualQuantities()[0].props('modelValue')).toBe(2);expect(rowChecks()[0].props('modelValue')).toBe(false);await excludeActualLine(0);
  expect(wrapper.findComponent(ElPagination).exists()).toBe(false);expect(document.querySelectorAll('.receipt-row')).toHaveLength(50);expect(actualQuantities()[0].props('modelValue')).toBe(7);
  expect(document.querySelector('.receipt-row')?.textContent).toContain('款式1');expect(api.save).not.toHaveBeenCalled();
 },60000)
 it('jumps to the page containing the first row that fails the original preview',async()=>{
  api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>({...response(input),canSave:false,rows:response(input).rows.map((row,index)=>({...row,canSave:index!==55,messages:index===55?['第56条需填写实际数量']:[]}))}));
  await start(Array.from({length:60},(_,i)=>style(i+1)));await fillCommon();expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(1);
  bulkModel().vm.$emit('update:modelValue',[18]);await fillBulk('黑色',2);await applyBulk();
  await click('保存实际收样');expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(2);expect(document.querySelectorAll('.receipt-row')).toHaveLength(10);
  expect(document.querySelector('.receipt-row-errors')?.textContent).toContain('第56条需填写实际数量');expect(api.save).not.toHaveBeenCalled();expect(lastPreviewInput().rows).toHaveLength(60);
 },30000)
 it('allows read-only page navigation for an unknown save while replaying every original row unchanged',async()=>{
  const rows=Array.from({length:51},(_,i)=>style(i+1)),input:SelectionReceiptInput={version:3,requestId:'pending-paged-original',date:'2026-10-27',location:'公司样品柜A',ownerUserId:71,ownerName:'国内工程',rows:rows.map((row,index)=>({clientRowId:`original-pending-${index}`,rowId:row.rowId,rowVersion:2,phoneModelId:18,colorName:'黑色',qty:1}))};
  sessionStorage.setItem('plm:selection-receipt:71:7',JSON.stringify({actor:'71',input,metadata:response(input).rows}));await start(rows);
  const pager=wrapper.getComponent(ElPagination);expect(pager.props()).toMatchObject({total:51,currentPage:1,disabled:false});expect(allStyleModelsButton()?.disabled).toBe(true);expect(document.querySelectorAll('.receipt-row')).toHaveLength(50);
  pager.vm.$emit('update:current-page',2);await flushPromises();expect(document.querySelectorAll('.receipt-row')).toHaveLength(1);expect(actualQuantities()[0].props('disabled')).toBe(true);expect(models()[0].props('disabled')).toBe(true);
  models()[0].vm.$emit('update:modelValue',[17]);allStyleModelsButton()!.click();await flushPromises();expect(models()[0].props('modelValue')).toEqual([18]);
  expect(api.preview).not.toHaveBeenCalled();await click('核对本次保存结果');expect(api.save).toHaveBeenCalledTimes(1);expect(api.save.mock.calls[0][1]).toEqual(input);expect(api.preview).not.toHaveBeenCalled();
 },15000)
})


describe('authoritative intended colours with per-style phone selection',()=>{
 it('prefills each styles intended colour names and expands only its own phones and colours once',async()=>{
  previewStylePhones({'row-1':[1,2,3],'row-2':[1,2]},{'row-1':['红色','蓝色'],'row-2':['黑色']});
  const rows=[{...style(1),phoneModelIds:[1,2,3],sampleColors:['过期缓存色A']},{...style(2),phoneModelIds:[1,2],sampleColors:['过期缓存色B']}],original=JSON.stringify(rows);
  await start(rows);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['红色','蓝色','黑色']);expect(models().map(input=>input.props('modelValue'))).toEqual([[],[],[]]);expect(actualQuantities().every(input=>input.props('modelValue')===2)).toBe(true);
  expect(rowChecks().every(input=>input.props('modelValue'))).toBe(true);await click('选择各款全部型号');
  expect(models().map(input=>input.props('modelValue'))).toEqual([[1],[1],[2],[2],[3],[3],[1],[2]]);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['红色','蓝色','红色','蓝色','红色','蓝色','黑色','黑色']);
  const expanded=lastPreviewInput().rows,tuples=[['row-1',1,'红色'],['row-1',1,'蓝色'],['row-1',2,'红色'],['row-1',2,'蓝色'],['row-1',3,'红色'],['row-1',3,'蓝色'],['row-2',1,'黑色'],['row-2',2,'黑色']];
  expect(expanded.map(row=>[row.rowId,row.phoneModelId,row.colorName])).toEqual(tuples);expect(new Set(expanded.map(row=>row.clientRowId)).size).toBe(8);expect(expanded.every(row=>row.qty===2&&row.colorId===undefined&&row.sampleIdentityId===undefined)).toBe(true);
  const elements=[...document.querySelectorAll('.receipt-row')],reads=api.preview.mock.calls.length;await click('选择各款全部型号');expect([...document.querySelectorAll('.receipt-row')]).toEqual(elements);expect(api.preview).toHaveBeenCalledTimes(reads);
  await fillCommon();await fillBulk('',2);await applyBulk();await click('保存实际收样');expect(api.save).toHaveBeenCalledTimes(1);
  const input=api.save.mock.calls[0][1] as SelectionReceiptInput;expect(input.rows.map(row=>[row.rowId,row.phoneModelId,row.colorName])).toEqual(tuples);expect(input.rows.every(row=>row.rowVersion===2&&row.qty===2&&!('sampleColors' in row))).toBe(true);expect(JSON.stringify(rows)).toBe(original);
 })
 it('preserves a filled intended colour and sample identity while later phone previews never reseed the form',async()=>{
  previewStylePhones({'row-1':[18,17]},{'row-1':['供方烟灰','白色']});api.candidates.mockResolvedValue({content:[{sampleIdentityId:101,sampleCode:'SY-000101',name:'已核对同物样品',specName:'原型号供方烟灰'}],canCreate:true,requiresReview:false});
  await start([{...style(1),phoneModelIds:[18,17],sampleColors:['旧缓存']}]);await fillCommon();expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['供方烟灰','白色']);
  const initialRows=[...document.querySelectorAll('.receipt-row')];models()[0].vm.$emit('update:modelValue',[18]);await flushPromises();await selectExistingColour(0,8,'供方烟灰',7);
  models()[1].vm.$emit('update:modelValue',[17]);await flushPromises();actualQuantities()[1].vm.$emit('update:modelValue',3);models()[1].vm.$emit('update:modelValue',[18]);await flushPromises();
  expect([...document.querySelectorAll('.receipt-row')]).toEqual(initialRows);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['供方烟灰','白色']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([7,3]);
  await click('选择各款全部型号');const expanded=lastPreviewInput().rows;
  expect([...document.querySelectorAll('.receipt-row')].slice(0,2)).toEqual(initialRows);expect(expanded).toHaveLength(4);
  expect(expanded.slice(0,2)).toEqual([expect.objectContaining({phoneModelId:18,colorId:8,colorName:'供方烟灰',sampleIdentityId:101,qty:7}),expect.objectContaining({phoneModelId:18,colorId:undefined,colorName:'白色',sampleIdentityId:undefined,qty:3})]);
  expect(expanded.slice(2).map(row=>[row.phoneModelId,row.colorName,row.qty,row.colorId,row.sampleIdentityId])).toEqual([[17,'供方烟灰',7,undefined,undefined],[17,'白色',3,undefined,undefined]]);
  actualQuantities()[2].vm.$emit('update:modelValue',1);actualQuantities()[3].vm.$emit('update:modelValue',2);await flushPromises();await click('保存实际收样');expect(api.save).toHaveBeenCalledTimes(1);expect((api.save.mock.calls[0][1] as SelectionReceiptInput).rows.slice(0,2)).toEqual(expanded.slice(0,2));
 })
 it('restores a pending coloured receipt and replays its original rows even when source intended colours change',async()=>{
  previewStylePhones({'row-1':[18,17]},{'row-1':['红色','蓝色']});api.save.mockRejectedValueOnce({code:'ECONNABORTED'});
  await start([{...style(1),phoneModelIds:[18,17]}]);await fillCommon();await click('选择各款全部型号');await fillBulk('',2);await applyBulk();await click('保存实际收样');
  const first=JSON.parse(JSON.stringify(api.save.mock.calls[0][1])) as SelectionReceiptInput;expect(first.rows).toHaveLength(4);expect(first.rows.map(row=>row.colorName)).toEqual(['红色','蓝色','红色','蓝色']);const reads=api.preview.mock.calls.length;
  wrapper.unmount();previewStylePhones({'row-1':[18,17]},{'row-1':['后续计划新色']});await start([{...style(1),phoneModelIds:[18,17],sampleColors:['后续缓存新色']}]);
  expect(api.preview).toHaveBeenCalledTimes(reads);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['红色','蓝色','红色','蓝色']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2,2,2]);expect(allStyleModelsButton()?.disabled).toBe(true);expect(allStyleColorsButton()?.disabled).toBe(true);allStyleModelsButton()!.click();allStyleColorsButton()!.click();await flushPromises();
  await click('核对本次保存结果');expect(api.save).toHaveBeenCalledTimes(2);expect(api.save.mock.calls[1][1]).toEqual(first);expect(api.preview).toHaveBeenCalledTimes(reads);expect(sessionStorage.getItem('plm:selection-receipt:71:7')).toBeNull();
 })
 it('keeps an initial authoritative source read failure visible instead of seeding cached intended colours',async()=>{
  api.preview.mockRejectedValueOnce(new Error('读取实收来源失败'));await start([{...style(1),sampleColors:['旧缓存色']}]);
  expect(document.body.textContent).toContain('读取实收来源失败');expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['']);expect(models().map(input=>input.props('modelValue'))).toEqual([[]]);expect(actualQuantities()[0].props('modelValue')).toBe(2);expect(api.preview).toHaveBeenCalledTimes(1);expect(api.save).not.toHaveBeenCalled();
 })
})

// The boundary supplies original per-style colours separately from broad supplier suggestions.
function previewStyleColors(phones:Record<string,number[]>,sourceColors:Record<string,string[]>,intended:Record<string,string[]>={}){
 api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>({...response(input),rows:response(input).rows.map(row=>({...row,phones:(phones[row.rowId]||[]).map(phoneModelId=>({phoneModelId,modelName:`型号${phoneModelId}`})),sourceColors:sourceColors[row.rowId]||[],sampleColors:intended[row.rowId]||[],receiptColorMode:'at_receipt'}))}))
}

describe('compact per-style receipt shortcuts',()=>{
 it('keeps the two all-style actions together and the manual apply action with its input fields',async()=>{
  await start();const shortcuts=document.querySelector('[role="group"][aria-label="按款快速填写"]')!
  expect(shortcuts).not.toBeNull();expect([...shortcuts.querySelectorAll('button')].map(button=>button.textContent?.trim())).toEqual(['选择各款全部型号','选择各款全部颜色']);
  expect(document.querySelector('.receipt-bulk-model')?.contains(allStyleModelsButton()!)).toBe(false);
  expect(document.querySelector('.receipt-bulk-inputs')?.contains(bulkButton())).toBe(true);expect(api.save).not.toHaveBeenCalled();
 })
 it('fills each styles original colours without using supplier-wide suggestions or cached source props and repeats without duplicating',async()=>{
  previewStyleColors({'row-1':[18,17],'row-2':[18]},{'row-1':['红色','蓝色'],'row-2':['黑色']});
  const rows=[{...style(1),color:'缓存旧色'},{...style(2),color:'缓存另色'}],original=JSON.stringify(rows);await start(rows);
  const reads=api.preview.mock.calls.length;await click('选择各款全部颜色');
  expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['红色','蓝色','黑色']);expect(actualQuantities().every(input=>input.props('modelValue')===2)).toBe(true);
  expect(models().map(input=>input.props('modelValue'))).toEqual([[],[],[18]]);const elements=[...document.querySelectorAll('.receipt-row')];await click('选择各款全部颜色');
  expect([...document.querySelectorAll('.receipt-row')]).toEqual(elements);expect(api.preview).toHaveBeenCalledTimes(reads);expect(JSON.stringify(rows)).toBe(original);
  await click('取消');expect(wrapper.emitted('close')).toHaveLength(1);expect(api.save).not.toHaveBeenCalled();
 })
 it('retains selected colour identities, actual quantities, exceptions and unchecked styles while only adding missing colours',async()=>{
  previewStyleColors({'row-1':[18],'row-2':[18]},{'row-1':['供方烟灰','白色'],'row-2':['黑色']});
  api.candidates.mockResolvedValue({content:[{sampleIdentityId:101,sampleCode:'SY-000101',name:'原同物',specName:'原规格'}],canCreate:true,requiresReview:false});
  await start();await fillCommon();await selectExistingColour(0,8,'供方烟灰',7);await selectExistingColour(1,9,'供方黑色',3);await checkLine(1,false);
  const before=JSON.parse(JSON.stringify(lastPreviewInput().rows)),original=[...document.querySelectorAll('.receipt-row')];await click('选择各款全部颜色');
  expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['供方烟灰','白色','供方黑色']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([7,2,3]);
  expect(document.querySelectorAll('.receipt-row')[0]).toBe(original[0]);expect(document.querySelectorAll('.receipt-row')[2]).toBe(original[1]);await fillBulk('',2);await applyBulk();
  expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2,3]);await click('保存实际收样');const rows=api.save.mock.calls[0][1].rows;
  expect(rows[0]).toMatchObject({clientRowId:before[0].clientRowId,colorId:8,sampleIdentityId:101,qty:2});expect(rows[2]).toMatchObject({clientRowId:before[1].clientRowId,colorId:9,sampleIdentityId:101,qty:3});
 })
 it('supports colours first then each styles own phones and sends all real rows through the original single save',async()=>{
  previewStyleColors({'row-1':[18,17],'row-2':[18]},{'row-1':['红色','蓝色'],'row-2':['黑色']});await start();await fillCommon();await click('选择各款全部颜色');await click('选择各款全部型号');
  const expected=[['row-1',18,'红色'],['row-1',18,'蓝色'],['row-1',17,'红色'],['row-1',17,'蓝色'],['row-2',18,'黑色']];
  expect(models().map(input=>input.props('modelValue'))).toEqual([[18],[18],[17],[17],[18]]);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['红色','蓝色','红色','蓝色','黑色']);expect(actualQuantities().every(input=>input.props('modelValue')===2)).toBe(true);
  await fillBulk('',2);await applyBulk();await click('保存实际收样');expect(api.save).toHaveBeenCalledTimes(1);expect(api.save.mock.calls[0][1].rows.map((row:SelectionReceiptInput['rows'][number])=>[row.rowId,row.phoneModelId,row.colorName])).toEqual(expected);
  expect(api.save.mock.calls[0][1].rows.every((row:SelectionReceiptInput['rows'][number])=>row.qty===2&&!row.sampleIdentityId)).toBe(true);
 })
 it('uses explicit sample colours before a broader original list and exposes shortcuts for one phone with multiple original colours',async()=>{
  previewStyleColors({'row-1':[18]},{'row-1':['原目录黑','原目录白']},{'row-1':['本次选红','本次选蓝']});await start([style(1)]);await click('选择各款全部颜色');expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['本次选红','本次选蓝']);
  wrapper.unmount();previewStyleColors({'row-1':[18]},{'row-1':['黑','白']});await start([style(1)]);expect(allStyleColorsButton()).toBeDefined();await click('选择各款全部颜色');expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['黑','白']);expect(models().map(input=>input.props('modelValue'))).toEqual([[18],[18]]);expect(api.save).not.toHaveBeenCalled();
 })
 it('does not partially fill earlier styles when one checked style has no explicit colours',async()=>{
  previewStyleColors({'row-1':[18,17],'row-2':[18,17]},{'row-1':['红','蓝']});await start();const elements=[...document.querySelectorAll('.receipt-row')];await click('选择各款全部颜色');
  expect([...document.querySelectorAll('.receipt-row')]).toEqual(elements);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['','']);expect(document.body.textContent).toContain('款式2尚未记录可带入的颜色');expect(api.save).not.toHaveBeenCalled();
 })
 it('keeps historical colours specific to each phone instead of expanding their union',async()=>{
  api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>({...response(input),rows:response(input).rows.map(row=>({...row,receiptColorMode:'selected_pairs',sourceColors:row.phoneModelId===18?['型号18黑']:row.phoneModelId===17?['型号17白']:[]}))}));
  await start([style(1)]);await click('选择各款全部型号');await click('选择各款全部颜色');expect(models().map(input=>input.props('modelValue'))).toEqual([[18],[17]]);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['型号18黑','型号17白']);expect(api.save).not.toHaveBeenCalled();
 })
 it('retains all 1170 rows in one payload while rendering only the current page',async()=>{
  const ids=Array.from({length:39},(_,i)=>i+1),rows=Array.from({length:15},(_,i)=>({...style(i+1),phoneModelIds:ids}));
  previewStyleColors(Object.fromEntries(rows.map(row=>[row.rowId,ids])),Object.fromEntries(rows.map(row=>[row.rowId,['黑','白']])));await start(rows);await fillCommon();await click('选择各款全部型号');await click('选择各款全部颜色');
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(50);expect(wrapper.getComponent(ElPagination).props('total')).toBe(1170);await fillBulk('',2);await applyBulk();await click('保存实际收样');
  const input=api.save.mock.calls[0][1] as SelectionReceiptInput;expect(api.save).toHaveBeenCalledTimes(1);expect(input.rows).toHaveLength(1170);expect(new Set(input.rows.map(row=>row.clientRowId)).size).toBe(1170);
  expect(input.rows.filter(row=>row.rowId==='row-15'&&row.phoneModelId===39).map(row=>row.colorName)).toEqual(['黑','白']);expect(input.rows.every(row=>row.qty===2)).toBe(true);
 },30000)
})

it('expands 1709 models locally with default two and validates a deliberate clear without a full-table read',async()=>{
 const rows=Array.from({length:28},(_,i)=>({...style(i+1),phoneModelIds:Array.from({length:i===27?62:61},(_,j)=>j+1)}))
 previewStyleColors(Object.fromEntries(rows.map(row=>[row.rowId,row.phoneModelIds])),Object.fromEntries(rows.map(row=>[row.rowId,['黑色']])));await start(rows);await fillCommon();
 const reads=api.preview.mock.calls.length;await click('选择各款全部型号');expect(wrapper.getComponent(ElPagination).props('total')).toBe(1709);expect(api.preview).toHaveBeenCalledTimes(reads);expect(allStyleModelsButton()?.disabled).toBe(false);
 await click('选择各款全部颜色');expect(actualQuantities().every(input=>input.props('modelValue')===2)).toBe(true);actualQuantities()[0].vm.$emit('update:modelValue',undefined);await flushPromises();await click('保存实际收样');expect(document.body.textContent).toContain('还有1条规格未填写');expect(api.preview).toHaveBeenCalledTimes(reads);expect(api.save).not.toHaveBeenCalled();
 api.save.mockRejectedValueOnce({code:'ECONNABORTED'});await fillBulk('',2);await applyBulk();await click('保存实际收样');expect(api.preview).toHaveBeenCalledTimes(reads+1);expect(api.save).toHaveBeenCalledTimes(1);expect(api.save.mock.calls[0][1].rows).toHaveLength(1709);const pending=JSON.parse(sessionStorage.getItem('plm:selection-receipt:71:7')!);expect(pending.metadata).toHaveLength(28);expect(pending.input.rows).toHaveLength(1709);wrapper.unmount();await start(rows);expect(api.preview).toHaveBeenCalledTimes(reads+1);expect(wrapper.getComponent(ElPagination).props('total')).toBe(1709);expect(models()[0].props('modelValue')).toEqual([1]);expect(colorInputs()[0].props('modelValue')).toBe('黑色');expect(actualQuantities()[0].props('modelValue')).toBe(2);
},60000)

it('retains complete inputs and allows a safe retry when only the read-only validation times out',async()=>{
 await start([style(1)]);await fillCommon();await fillActual(0,2);api.preview.mockRejectedValueOnce({code:'ECONNABORTED'});await click('保存实际收样');
 expect(document.body.textContent).toContain('收样资料校验暂未完成，填写内容已保留');expect(actualQuantities()[0].props('modelValue')).toBe(2);expect(api.save).not.toHaveBeenCalled();expect(sessionStorage.getItem('plm:selection-receipt:71:7')).toBeNull();
 await click('保存实际收样');expect(api.save).toHaveBeenCalledTimes(1);
})


describe('current supplier scope and actual defaults',()=>{
 it('keeps deliberately cleared quantities while new phone and colour rows start at two and the location remains editable',async()=>{
  previewStylePhones({'row-1':[18,17]},{'row-1':['黑色','白色']});await start([style(1)]);
  expect(wrapper.getComponent(SampleLocationInput).props()).toMatchObject({modelValue:'CN',site:'CN'});expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2]);
  actualQuantities()[0].vm.$emit('update:modelValue',undefined);await flushPromises();await click('选择各款全部型号');
  expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([undefined,2,undefined,2]);
  expect(models().map(input=>input.props('modelValue'))).toEqual([[18],[18],[17],[17]]);expect(api.save).not.toHaveBeenCalled();
 })
 it('sends the latest authoritative token for each actual client row without copying planned quantities',async()=>{
  let revision=1;api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>({...response(input),rows:response(input).rows.map(row=>({...row,sourceScopeToken:`${row.clientRowId}:r${revision}`}))}));
  await start();await fillCommon();await fillActual(0,3);await fillActual(1,4);revision=2;await click('保存实际收样');
  const input=api.save.mock.calls[0][1] as SelectionReceiptInput;expect(input.rows.every(row=>row.sourceScopeToken===`${row.clientRowId}:r2`)).toBe(true);expect(input.rows.map(row=>row.qty)).toEqual([3,4]);expect(input.rows.every(row=>!('sampleQty' in row))).toBe(true);
 })
 it('retains inputs and refreshes supplier options when the source changes between preview and saving',async()=>{
  api.save.mockRejectedValueOnce(new Error('供方型号范围已变化或尚未预览，请保留输入并重新核对收样'));
  await start([style(1)]);await fillCommon();await fillActual(0,7);const reads=api.preview.mock.calls.length;await click('保存实际收样');expect(document.body.textContent).toContain('供方型号范围已变化');
  expect(actualQuantities()[0].props('modelValue')).toBe(7);expect(colorInputs()[0].props('modelValue')).toBe('供方烟灰');expect(models()[0].props('modelValue')).toEqual([18]);expect(api.preview).toHaveBeenCalledTimes(reads+2);
  await click('保存实际收样');expect(api.save.mock.calls[1][1].rows).toEqual(api.save.mock.calls[0][1].rows);
 })
 it('restores an uncertain legacy request with a cleared quantity and original token without applying new defaults',async()=>{
  const input:SelectionReceiptInput={version:3,requestId:'original-blank-pending',date:'2026-10-27',location:'原存放位置',ownerName:'原保管人',rows:[{clientRowId:'old',rowId:'row-1',rowVersion:2,phoneModelId:18,colorName:'黑色',sourceScopeToken:'old-token'}]};
  sessionStorage.setItem('plm:selection-receipt:71:7',JSON.stringify({actor:'71',input,metadata:response(input).rows}));await start([style(1)]);
  expect(actualQuantities()[0].props('modelValue')).toBeUndefined();expect(wrapper.getComponent(SampleLocationInput).props('modelValue')).toBe('原存放位置');await click('核对本次保存结果');expect(api.save.mock.calls[0][1]).toEqual(input);expect(api.preview).not.toHaveBeenCalled();
 })
})

describe('inherit checked receipt specifications and skip unsupported placeholders',()=>{
 it('inherits an existing orange quantity and keeps the existing identity only on its original phone',async()=>{
  previewStyleColors({'row-1':[18,17]},{'row-1':['橙色']});api.candidates.mockResolvedValue({content:[{sampleIdentityId:101,sampleCode:'SY-000101',name:'原橙色样品',specName:'原型号'}],canCreate:true,requiresReview:false});
  await start([style(1)]);await fillCommon();models()[0].vm.$emit('update:modelValue',[18]);await selectExistingColour(0,8,'橙色',2);bulkModel().vm.$emit('update:modelValue',[18,17]);await flushPromises();await applyBulk();
  expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['橙色','橙色']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2]);const elements=[...document.querySelectorAll('.receipt-row')];await applyBulk();expect([...document.querySelectorAll('.receipt-row')]).toEqual(elements);
  await click('保存实际收样');expect(api.save.mock.calls[0][1].rows).toEqual([expect.objectContaining({phoneModelId:18,colorId:8,sampleIdentityId:101,qty:2}),expect.objectContaining({phoneModelId:17,colorId:undefined,colorName:'橙色',sampleIdentityId:undefined,identityReason:undefined,qty:2})]);
 })
 it('copies each checked colour with its own quantity to a newly selected phone without reseeding a cleared colour',async()=>{
  previewStyleColors({'row-1':[18,17]},{},{'row-1':['黑色','白色','已清空原色']});await start([style(1)]);await fillCommon();await chooseAllPhones();actualQuantities()[0].vm.$emit('update:modelValue',2);actualQuantities()[1].vm.$emit('update:modelValue',3);await typeColor(2,'');await checkLine(2,false);
  bulkModel().vm.$emit('update:modelValue',[17]);await flushPromises();await applyBulk();expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['黑色','白色','','黑色','白色']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,3,2,2,3]);expect(models().map(input=>input.props('modelValue'))).toEqual([[18],[18],[18],[17],[17]]);expect(api.save).not.toHaveBeenCalled();
 })
 it('leaves a new phones conflicting same-colour quantity blank and preserves both entered quantities',async()=>{
  previewStyleColors({'row-1':[18,17,16]},{},{'row-1':['黑色']});await start([style(1)]);await fillCommon();models()[0].vm.$emit('update:modelValue',[18]);actualQuantities()[0].vm.$emit('update:modelValue',2);await addActualLine(0);models()[1].vm.$emit('update:modelValue',[17]);await typeColor(1,'黑色');actualQuantities()[1].vm.$emit('update:modelValue',3);await checkLine(1,true);
  bulkModel().vm.$emit('update:modelValue',[16]);await flushPromises();await applyBulk();expect(models().map(input=>input.props('modelValue'))).toEqual([[18],[17],[16]]);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,3,undefined]);expect(document.body.textContent).toContain('本款同色数量不一致，请填写本型号实际数量');await click('保存实际收样');expect(api.save).not.toHaveBeenCalled();
 })
 it('excludes only an unsupported placeholder from the original save and retains it for explicit restoration',async()=>{
  previewStyleColors({'row-1':[18,17],'row-2':[16,15]},{},{'row-1':['橙色'],'row-2':['蓝色']});const rows=[style(1),style(2)],original=JSON.stringify(rows);await start(rows);await fillCommon();actualQuantities()[1].vm.$emit('update:modelValue',7);bulkModel().vm.$emit('update:modelValue',[18]);await flushPromises();await applyBulk();
  expect(models().map(input=>input.props('modelValue'))).toEqual([[18]]);expect(document.body.textContent).toContain('本次跳过');expect(document.body.textContent).toContain('款式2');expect(JSON.stringify(rows)).toBe(original);await click('恢复本次收样');expect(models().map(input=>input.props('modelValue'))).toEqual([[18],[]]);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['橙色','蓝色']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,7]);expect(api.save).not.toHaveBeenCalled();
  await applyBulk();await click('保存实际收样');expect(api.save).toHaveBeenCalledTimes(1);expect(api.save.mock.calls[0][1].rows).toEqual([expect.objectContaining({rowId:'row-1',phoneModelId:18,colorName:'橙色',qty:2})]);
 })
 it('keeps a no-match style with an already valid phone and a wholly unchecked style unchanged',async()=>{
  previewStyleColors({'row-1':[18,17],'row-2':[16,15],'row-3':[18,17]},{},{'row-1':['橙色'],'row-2':['蓝色'],'row-3':['白色']});await start([style(1),style(2),style(3)]);models()[1].vm.$emit('update:modelValue',[16]);actualQuantities()[1].vm.$emit('update:modelValue',7);await checkLine(2,false);const originals=[...document.querySelectorAll('.receipt-row')];bulkModel().vm.$emit('update:modelValue',[18]);await flushPromises();await applyBulk();
  expect(models().map(input=>input.props('modelValue'))).toEqual([[18],[16],[]]);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['橙色','蓝色','白色']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,7,2]);expect(document.querySelectorAll('.receipt-row')[1]).toBe(originals[1]);expect(document.querySelectorAll('.receipt-row')[2]).toBe(originals[2]);expect([...document.querySelectorAll('button')].some(button=>button.textContent?.trim()==='恢复本次收样')).toBe(false);expect(api.save).not.toHaveBeenCalled();
 })
 it('keeps the whole form unchanged when every checked style has no usable model',async()=>{
  previewStyleColors({'row-1':[],'row-2':[]},{},{'row-1':['橙色'],'row-2':['蓝色']});await start();actualQuantities()[0].vm.$emit('update:modelValue',4);const elements=[...document.querySelectorAll('.receipt-row')],reads=api.preview.mock.calls.length;await click('选择各款全部型号');expect([...document.querySelectorAll('.receipt-row')]).toEqual(elements);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([4,2]);expect(models().map(input=>input.props('modelValue'))).toEqual([[],[]]);expect(document.body.textContent).toContain('本次没有可添加型号');expect(document.body.textContent).not.toContain('本次跳过');expect(api.preview).toHaveBeenCalledTimes(reads);expect(api.save).not.toHaveBeenCalled();
 })
 it('replays an unknown partial receipt without restoring or resubmitting its skipped placeholder',async()=>{
  previewStyleColors({'row-1':[18,17],'row-2':[16,15]},{},{'row-1':['橙色'],'row-2':['蓝色']});api.save.mockRejectedValueOnce({code:'ECONNABORTED'});await start();await fillCommon();bulkModel().vm.$emit('update:modelValue',[18]);await flushPromises();await applyBulk();await click('保存实际收样');const first=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));expect(first.rows).toHaveLength(1);wrapper.unmount();await start();expect(models()).toHaveLength(1);expect(document.body.textContent).toContain('本次跳过');const restore=[...document.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim()==='恢复本次收样')!;expect(restore.disabled).toBe(true);restore.click();await flushPromises();await click('核对本次保存结果');expect(api.save.mock.calls[1][1]).toEqual(first);expect(api.save.mock.calls[1][1].rows.some((row:SelectionReceiptInput['rows'][number])=>row.rowId==='row-2')).toBe(false);
 })
})


describe('native quantity control display',()=>{
 it('keeps actual input text and ARIA in sync with the saved quantity after typing and leaving the control',async()=>{
  await start();await fillCommon();await fillActual(0,2);await fillActual(1,2);
  const input=actualQuantities()[0].get('input');await input.setValue('3');await input.trigger('blur');await flushPromises();
  expect((actualQuantities()[0].get('input').element as HTMLInputElement).value).toBe('3');expect(actualQuantities()[0].get('input').attributes('aria-valuenow')).toBe('3');expect(actualQuantities()[0].props('modelValue')).toBe(3);expect(document.body.textContent).toContain('合计 5 件');
  await click('保存实际收样');expect(api.save.mock.calls[0][1].rows.map((row:SelectionReceiptInput['rows'][number])=>row.qty)).toEqual([3,2]);
 })
})


describe('explicitly unspecified supplier models at actual receipt',()=>{
 function scopeModes(modes:Record<string,'supplier'|'unspecified'|undefined>,phones:Record<string,number[]>={}){
  api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>({...response(input),rows:response(input).rows.map(row=>({...row,receiptPhoneScopeMode:modes[row.rowId],receiptColorMode:'at_receipt',phones:(phones[row.rowId]||[]).map(phoneModelId=>({phoneModelId,modelName:`型号${phoneModelId}`})),sampleColors:['黑色']}))}));
 }
 it('opens the existing library only for an authoritative unspecified mode and never automatically selects its hydrated phone',async()=>{
  scopeModes({'row-1':'unspecified'},{'row-1':[18]});await start([style(1),style(2)]);
  expect(models()[0].props()).toMatchObject({modelValue:[],multiple:false});expect(models()[0].props('allowedIds')).toBeUndefined();expect(models()[1].props('allowedIds')).toEqual([]);expect(bulkModel().props('allowedIds')).toBeUndefined();
  expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2]);expect(wrapper.getComponent(SampleLocationInput).props('modelValue')).toBe('CN');expect(wrapper.getComponent(SampleCustodianSelect).props('modelValue')).toBe(71);
  expect(document.body.textContent).toContain('供应商未注明型号，请按实物选择');expect(api.save).not.toHaveBeenCalled();
 })
 it('expands known per-style phones while skipping an unspecified placeholder instead of guessing its library choices',async()=>{
  scopeModes({'row-1':'supplier','row-2':'unspecified'},{'row-1':[18,17],'row-2':[18,17,16]});await start();
  await click('选择各款全部型号');expect(models().map(model=>model.props('modelValue'))).toEqual([[18],[17]]);expect(document.querySelectorAll('.receipt-row')).toHaveLength(2);expect(document.body.textContent).toContain('不能按供方全选');expect(document.body.textContent).toContain('本次跳过');expect(api.save).not.toHaveBeenCalled();
 })
 it('allows explicit batch model choices for unspecified styles while keeping known supplier membership checks',async()=>{
  scopeModes({'row-1':'supplier','row-2':'unspecified'},{'row-1':[18]});await start();bulkModel().vm.$emit('update:modelValue',[18,17]);await flushPromises();
  await applyBulk();expect(models().map(model=>model.props('modelValue'))).toEqual([[18],[18],[17]]);await click('保存实际收样');const input=api.save.mock.calls[0][1] as SelectionReceiptInput;
  expect(input.rows.map(row=>[row.rowId,row.phoneModelId,row.colorName,row.qty])).toEqual([['row-1',18,'黑色',2],['row-2',18,'黑色',2],['row-2',17,'黑色',2]]);expect(input.rows.every(row=>row.sourceScopeToken===`scope-${row.rowId}`)).toBe(true);expect(api.save).toHaveBeenCalledTimes(1);
 })
 it('keeps a manually chosen library model and actual inputs after a source-lock conflict without creating an uncertain replay',async()=>{
  scopeModes({'row-1':'unspecified'});api.save.mockRejectedValueOnce(Object.assign(new Error('目录来源正在更新，请保留输入后重试'),{response:{status:409}}));await start([style(1)]);models()[0].vm.$emit('update:modelValue',[17]);await flushPromises();actualQuantities()[0].vm.$emit('update:modelValue',5);await click('保存实际收样');
  expect(models()[0].props('modelValue')).toEqual([17]);expect(actualQuantities()[0].props('modelValue')).toBe(5);expect(document.body.textContent).toContain('目录来源正在更新');expect(document.body.textContent).not.toContain('保存结果待核对');expect(sessionStorage.getItem('plm:selection-receipt:71:7')).toBeNull();await click('保存实际收样');expect(api.save.mock.calls[1][1]).toEqual(api.save.mock.calls[0][1]);
 })
 it('replays an uncertain unspecified request exactly while all library and all-model actions stay locked',async()=>{
  scopeModes({'row-1':'unspecified'});api.save.mockRejectedValueOnce({code:'ECONNABORTED'});await start([style(1)]);models()[0].vm.$emit('update:modelValue',[17]);await flushPromises();await click('保存实际收样');const first=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));wrapper.unmount();await start([style(1)]);
  expect(models()[0].props('disabled')).toBe(true);expect(models()[0].props('modelValue')).toEqual([17]);models()[0].vm.$emit('update:modelValue',[18]);await flushPromises();expect(models()[0].props('modelValue')).toEqual([17]);expect(api.save).toHaveBeenCalledTimes(1);await click('核对本次保存结果');expect(api.save.mock.calls[1][1]).toEqual(first);
 })
})
