import {flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {useUserStore} from '@/stores/user'
import {createMemoryHistory,createRouter} from 'vue-router'
import ElementPlus,{ElCheckbox,ElDatePicker,ElInput,ElInputNumber,ElSelect} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import ReceiptDialog from '../selection/SelectionReceiptDialog.vue'
import {newSelectionRow} from '@/utils/partnerSelections'
import type {SelectionReceiptInput,SelectionReceiptResult} from '@/types/partnerSelections'

const api=vi.hoisted(()=>({preview:vi.fn(),save:vi.fn(),candidates:vi.fn(),identities:vi.fn()}))
vi.mock('@/api/modules/sampleManagement',()=>({sampleIdentityCandidates:api.candidates,sampleIdentities:api.identities}))
vi.mock('@/api/modules/partnerSelections',()=>({previewSelectionReceipts:api.preview,saveSelectionReceipts:api.save}))
vi.mock('../PartnerPhoto.vue',()=>({default:{props:['id'],template:'<span class="partner-photo">本行图片{{id}}</span>'}}))
const style=(index:number)=>({...newSelectionRow(),rowId:`row-${index}`,rowVersion:2,catalogStyleId:index,catalogStyleLabel:`XD-CP-00000${index}`,name:`款式${index}`,supplierCode:'SUP-XD',confirmed:false,phoneModelIds:[18],sampleQty:99})
const response=(input:SelectionReceiptInput)=>({id:7,version:3,canSave:!!input.date&&!!input.location&&!!input.ownerName&&input.rows.every(row=>row.phoneModelId&&(row.colorId||row.colorName?.trim())&&Number(row.qty)>0),messages:input.date&&input.location&&input.ownerName?[]:['请填写实际入库日期、位置和保管人'],rows:input.rows.map(row=>({...row,name:`款式${row.rowId.slice(-1)}`,catalogStyleLabel:'XD-CP-000001',supplierCode:'SUP-XD',phones:[{phoneModelId:18,modelName:'iPhone 18 Pro Max'},{phoneModelId:17,modelName:'iPhone 17 Pro'}],colors:[{id:8,name:'供方烟灰'},{id:9,name:'供方黑色'}],refs:[{id:71,name:'本款.png'}],messages:row.phoneModelId&&(row.colorId||row.colorName?.trim())&&Number(row.qty)>0?[]:['请选择实收型号、颜色和数量'],canSave:!!row.phoneModelId&&!!(row.colorId||row.colorName?.trim())&&Number(row.qty)>0}))})
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
beforeEach(()=>{vi.clearAllMocks();sessionStorage.clear();setActivePinia(createPinia());useUserStore().profile={userId:71,userName:'国内工程',roleName:'工程',department:'工程'};api.candidates.mockResolvedValue({content:[],canCreate:true,requiresReview:false});api.identities.mockResolvedValue({content:[]});api.preview.mockImplementation(async(_id,input)=>response(input));api.save.mockImplementation(async(_id,input)=>saved(input))})
afterEach(()=>{wrapper?.unmount();document.body.innerHTML='';sessionStorage.clear()})
async function start(rows=[style(1),style(2)]){const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push('/partner-selections?id=7');await router.isReady();wrapper=mount(ReceiptDialog,{props:{id:7,version:3,rows,from:'/partner-selections?id=7'},attachTo:document.body,global:{plugins:[router,ElementPlus]}});await flushPromises();return router}
const click=async(text:string)=>{const button=[...document.querySelectorAll('button')].find(button=>button.textContent?.trim()===text);expect(button,`button ${text}`).toBeDefined();button!.click();await flushPromises()}
async function fillCommon(){wrapper.getComponent(ElDatePicker).vm.$emit('update:modelValue','2026-10-27');wrapper.findAllComponents(ElInput).find(input=>input.props('ariaLabel')==='存放位置')!.vm.$emit('update:modelValue','公司样品柜A');wrapper.findAllComponents(ElInput).find(input=>input.props('ariaLabel')==='保管人')!.vm.$emit('update:modelValue','国内工程');await flushPromises()}
const actualQuantities=()=>wrapper.findAllComponents(ElInputNumber).filter(input=>!String(input.props('ariaLabel')||'').startsWith('批量实收数量'))
const bulkColor=(index=0)=>wrapper.findAllComponents(ElInput).filter(input=>String(input.props('ariaLabel')||'').startsWith('批量实收颜色'))[index]!
const bulkQty=(index=0)=>wrapper.findAllComponents(ElInputNumber).filter(input=>String(input.props('ariaLabel')||'').startsWith('批量实收数量'))[index]!
const rowChecks=()=>wrapper.findAllComponents(ElCheckbox).filter(checkbox=>checkbox.classes().includes('receipt-row-check'))
const allCheck=()=>wrapper.findAllComponents(ElCheckbox).find(checkbox=>checkbox.props('ariaLabel')==='全选本次收样规格')!
const bulkButton=()=>[...document.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim().startsWith('应用到已勾选'))!
async function fillBulk(colorName:string,qty?:number,index=0){bulkColor(index).vm.$emit('update:modelValue',colorName);bulkQty(index).vm.$emit('update:modelValue',qty);await flushPromises()}
async function applyBulk(){expect(bulkButton()).toBeDefined();bulkButton().click();await flushPromises()}
async function checkLine(index:number,checked:boolean){rowChecks()[index]!.vm.$emit('change',checked);await flushPromises()}
const models=()=>wrapper.findAllComponents(ElSelect).filter(input=>String(input.props('ariaLabel')||'').endsWith('实收型号'))
const colorInputs=()=>wrapper.findAllComponents({name:'ElAutocomplete'})
async function chooseAllPhones(id=18){models().forEach(model=>model.vm.$emit('update:modelValue',id));await flushPromises()}
async function fillTwoColours(first='黑色',firstQty=2,second='白色',secondQty=2){await fillBulk(first,firstQty);await click('增加颜色');await fillBulk(second,secondQty,1)}
async function addActualLine(index:number){const row=[...document.querySelectorAll('.receipt-row')][index];[...row.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim()==='增加规格')!.click();await flushPromises()}
async function excludeActualLine(index:number){const row=[...document.querySelectorAll('.receipt-row')][index];[...row.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim()==='本次不收')!.click();await flushPromises()}
async function selectExistingColour(index:number,id:number,name:string,qty:number){colorInputs()[index].vm.$emit('select',{id,value:name,name});actualQuantities()[index].vm.$emit('update:modelValue',qty);await flushPromises()}

async function fillActual(index:number,qty=2){const models=wrapper.findAllComponents(ElSelect).filter(s=>String(s.props('ariaLabel')||'').endsWith('实收型号'));models[index]!.vm.$emit('update:modelValue',18);wrapper.findAllComponents({name:'ElAutocomplete'})[index]!.vm.$emit('select',{id:8,value:'供方烟灰',name:'供方烟灰',description:''});actualQuantities()[index]!.vm.$emit('update:modelValue',qty);await flushPromises()}
async function typeColor(index:number,colorName:string){const input=wrapper.findAllComponents({name:'ElAutocomplete'})[index]!.get('input');await input.setValue(colorName);await input.trigger('blur');await flushPromises()}

describe('actual multi-style sample receipt',()=>{
 it('shows adopted photos and supplier-scoped choices, with shared fields once and no planned quantity default',async()=>{await start();expect([...document.querySelectorAll('.receipt-common')]).toHaveLength(1);expect([...document.querySelectorAll('.receipt-row')]).toHaveLength(2);expect([...document.querySelectorAll('.partner-photo')]).toHaveLength(2);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([undefined,undefined]);expect(wrapper.findAllComponents(ElSelect).flatMap(select=>select.findAllComponents({name:'ElOption'}).map(option=>option.props('label')))).not.toContain('全库型号');expect(document.body.textContent).not.toContain('请选择实收型号、颜色和数量');expect(api.save).not.toHaveBeenCalled();expect(api.preview.mock.calls[0][1].rows.every((row:{qty?:number})=>row.qty===undefined)).toBe(true)})
 it('validates missing actual data without losing the inputs or silently saving any rows',async()=>{await start();await fillCommon();await fillActual(0,3);await click('保存实际收样');expect(document.body.textContent).toContain('请选择实收型号、颜色和数量');expect(actualQuantities()[0].props('modelValue')).toBe(3);expect(actualQuantities()[1].props('modelValue')).toBeUndefined();expect(api.save).not.toHaveBeenCalled();expect([...document.querySelectorAll('button')].some(button=>button.textContent?.trim()==='预览本次收样')).toBe(false)})
 it('validates internally and directly saves multiple actual specifications once and links only the real returned SP batches',async()=>{const router=await start();await fillCommon();await fillActual(0,3);await fillActual(1,4);[...document.querySelectorAll('.receipt-row')][0].querySelectorAll('button').forEach(button=>{if(button.textContent?.trim()==='增加规格')button.click()});await flushPromises();expect([...document.querySelectorAll('.receipt-row')]).toHaveLength(3);expect(actualQuantities()[2].props('modelValue')).toBeUndefined();await fillActual(2,1);expect(document.body.textContent).toContain('合计 8 件');await click('保存实际收样');expect(api.save).toHaveBeenCalledTimes(1);const input=api.save.mock.calls[0][1];expect(input).toMatchObject({date:'2026-10-27',location:'公司样品柜A',ownerName:'国内工程'});expect(input.rows.map((row:{rowId:string;qty:number})=>[row.rowId,row.qty])).toEqual([['row-1',3],['row-2',4],['row-1',1]]);expect(input.rows.every((row:Record<string,unknown>)=>!('sampleQty'in row))).toBe(true);expect(document.body.textContent).toContain('SY-000101');expect(document.body.textContent).toContain('SY-000102');expect(wrapper.emitted('saved')).toHaveLength(1);await click('内部收样原记录');expect(router.currentRoute.value.query.id).toBe('41')})
 it('retains a failed save and reconciles timeout with the exact same request, including after dialog remount',async()=>{api.save.mockRejectedValueOnce({code:'ECONNABORTED'});await start([style(1)]);await fillCommon();await fillActual(0,6);await click('保存实际收样');const first=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));expect(document.body.textContent).toContain('保存结果待核对');expect([...document.querySelectorAll('button')].find(button=>button.textContent?.trim()==='取消')!.getAttribute('disabled')).toBeDefined();expect(sessionStorage.getItem('plm:selection-receipt:71:7')).toContain(first.requestId);wrapper.unmount();await start([style(1)]);expect(document.body.textContent).toContain('上次保存结果尚未核对');await click('核对本次保存结果');expect(api.save.mock.calls[1][1]).toEqual(first);expect(sessionStorage.getItem('plm:selection-receipt:71:7')).toBeNull();expect(document.body.textContent).toContain('实际收样已保存')})
 it('directly accepts a new colour name and saves in the same receipt without opening supplier maintenance',async()=>{await start([style(1)]);expect(document.querySelector('.receipt-bulk')).toBeNull();expect(rowChecks()).toHaveLength(0);await fillCommon();const model=wrapper.findAllComponents(ElSelect).find(s=>String(s.props('ariaLabel')||'').endsWith('实收型号'))!;model.vm.$emit('update:modelValue',18);wrapper.getComponent(ElInputNumber).vm.$emit('update:modelValue',5);await typeColor(0,'湖蓝色');expect(wrapper.getComponent({name:'ElAutocomplete'}).props('modelValue')).toBe('湖蓝色');expect(document.body.textContent).not.toContain('维护供方颜色');expect(document.body.textContent).not.toContain('批量录入原色');expect(api.candidates).not.toHaveBeenCalled();expect(api.save).not.toHaveBeenCalled();await click('保存实际收样');expect(api.save.mock.calls[0][1]).toMatchObject({location:'公司样品柜A',ownerName:'国内工程',rows:[expect.objectContaining({phoneModelId:18,colorName:'湖蓝色',colorId:undefined,qty:5})]});expect(document.body.textContent).toContain('湖蓝色');expect(document.body.textContent).toContain('实际收样已保存')})
 it('offers existing colours in place and discards an old colour/identity when its text changes',async()=>{api.candidates.mockResolvedValue({content:[{sampleIdentityId:71,sampleCode:'SY-000071',name:'款式1',specName:'iPhone18灰色'}],canCreate:true,requiresReview:false});await start([style(1)]);await fillCommon();await fillActual(0,3);expect(wrapper.getComponent({name:'ElAutocomplete'}).props('modelValue')).toBe('供方烟灰');const suggest=wrapper.getComponent({name:'ElAutocomplete'}).props('fetchSuggestions') as (query:string,done:(items:unknown[])=>void)=>void;const done=vi.fn();suggest('黑',done);expect(done).toHaveBeenCalledWith([expect.objectContaining({id:9,value:'供方黑色'})]);expect(api.candidates).toHaveBeenCalledWith(expect.objectContaining({colorId:8,phoneModelId:18}));const count=api.candidates.mock.calls.length;await typeColor(0,'珠光白');expect(api.candidates).toHaveBeenCalledTimes(count);await click('保存实际收样');expect(api.save.mock.calls[0][1].rows[0]).toMatchObject({colorName:'珠光白',colorId:undefined,sampleIdentityId:undefined,identityReason:undefined})})
 it('preserves entered colour text after changing the actual phone while clearing the former colour ID',async()=>{await start([style(1)]);await fillCommon();await fillActual(0,3);const model=wrapper.findAllComponents(ElSelect).find(s=>String(s.props('ariaLabel')||'').endsWith('实收型号'))!;model.vm.$emit('update:modelValue',17);model.vm.$emit('change',17);await flushPromises();expect(wrapper.getComponent({name:'ElAutocomplete'}).props('modelValue')).toBe('供方烟灰');await click('保存实际收样');expect(api.save.mock.calls[0][1].rows[0]).toMatchObject({phoneModelId:17,colorName:'供方烟灰',colorId:undefined,sampleIdentityId:undefined})})
 it('retains a newly typed colour in the exact timeout replay across remounts',async()=>{api.save.mockRejectedValueOnce({code:'ECONNABORTED'});await start([style(1)]);await fillCommon();await fillActual(0,2);await typeColor(0,'冰透蓝');await click('保存实际收样');const first=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));expect(first.rows[0]).toMatchObject({colorName:'冰透蓝'});expect(first.rows[0]).not.toHaveProperty('colorId');wrapper.unmount();await start([style(1)]);expect(wrapper.getComponent({name:'ElAutocomplete'}).props('modelValue')).toBe('冰透蓝');await click('核对本次保存结果');expect(api.save.mock.calls[1][1]).toEqual(first);expect(document.body.textContent).toContain('实际收样已保存')})
 it('shows a pending legacy colour ID without changing the saved replay payload',async()=>{const input:SelectionReceiptInput={version:3,requestId:'legacy-receipt-request',date:'2026-10-27',location:'公司样品柜A',ownerName:'国内工程',rows:[{clientRowId:'old-row',rowId:'row-1',rowVersion:2,phoneModelId:18,colorId:8,qty:2}]};sessionStorage.setItem('plm:selection-receipt:71:7',JSON.stringify({actor:'71',input,metadata:response(input).rows}));await start([style(1)]);expect(wrapper.getComponent({name:'ElAutocomplete'}).props('modelValue')).toBe('供方烟灰');await click('核对本次保存结果');expect(api.save.mock.calls[0][1]).toEqual(input);expect(api.save.mock.calls[0][1].rows[0]).not.toHaveProperty('colorName')})
 it('keeps ambiguous existing colours distinct and lets the engineer choose one in the same field',async()=>{const choices=[{id:8,name:'黑色',code:'SC-000008',supplierColorCode:'BLACK-A',configKey:'亮面'},{id:9,name:'黑色',code:'SC-000009',supplierColorCode:'BLACK-B',configKey:'磨砂'}];api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>{const data=response(input);const ambiguous=input.rows.some(row=>row.colorName==='黑色'&&!row.colorId);return {...data,canSave:data.canSave&&!ambiguous,rows:data.rows.map(row=>({...row,colors:choices,messages:ambiguous?['有多个同名颜色，请在当前颜色框选择实际收到的颜色']:row.messages}))}});await start([style(1)]);await fillCommon();const model=wrapper.findAllComponents(ElSelect).find(s=>String(s.props('ariaLabel')||'').endsWith('实收型号'))!;model.vm.$emit('update:modelValue',18);wrapper.getComponent(ElInputNumber).vm.$emit('update:modelValue',2);await typeColor(0,'黑色');await click('保存实际收样');expect(api.save).not.toHaveBeenCalled();expect(document.body.textContent).toContain('有多个同名颜色');const suggestions=vi.fn();(wrapper.getComponent({name:'ElAutocomplete'}).props('fetchSuggestions') as (query:string,done:(items:unknown[])=>void)=>void)('黑',suggestions);expect(suggestions.mock.calls[0][0]).toEqual([expect.objectContaining({id:8,description:'色号 BLACK-A · 配置 亮面'}),expect.objectContaining({id:9,description:'色号 BLACK-B · 配置 磨砂'})]);wrapper.getComponent({name:'ElAutocomplete'}).vm.$emit('select',suggestions.mock.calls[0][0][1]);await flushPromises();await click('保存实际收样');expect(api.save.mock.calls[0][1].rows[0]).toMatchObject({colorId:9,colorName:'黑色'})})
 it('distinguishes existing same-name records in the inline suggestions without showing codes for ordinary colours',async()=>{api.preview.mockImplementation(async(_id,input:SelectionReceiptInput)=>({...response(input),rows:response(input).rows.map(row=>({...row,colors:[{id:8,name:'黑色',code:'SC-000008'},{id:9,name:'黑色',code:'SC-000009'},{id:10,name:'蓝色',code:'SC-000010'}]}))}));await start([style(1)]);const suggestions=vi.fn();(wrapper.getComponent({name:'ElAutocomplete'}).props('fetchSuggestions') as (query:string,done:(items:unknown[])=>void)=>void)('',suggestions);expect(suggestions.mock.calls[0][0]).toEqual([expect.objectContaining({id:8,description:'记录 SC-000008'}),expect.objectContaining({id:9,description:'记录 SC-000009'}),expect.objectContaining({id:10,description:''})]);expect(api.save).not.toHaveBeenCalled()})
 it('keeps pending receipts isolated by the real logged-in user and blocks an account change during entry',async()=>{await start([style(1)]);await fillCommon();await fillActual(0,3);useUserStore().profile={userId:72,userName:'另一账号',roleName:'工程',department:'工程'};await flushPromises();expect(document.body.textContent).toContain('登录账号已改变');expect(document.querySelector('.receipt-row')).toBeNull();expect(document.querySelector('.receipt-bulk')).toBeNull();expect(api.save).not.toHaveBeenCalled()})

 it('updates native actual-quantity ARIA after loading and retains actual input when a save becomes ready after failure',async()=>{let resolveOptions!:(value:ReturnType<typeof response>)=>void;api.preview.mockImplementationOnce(()=>new Promise(resolve=>{resolveOptions=resolve}));api.save.mockRejectedValueOnce(new Error('暂时无法保存'));await start([style(1)]);const disabled=document.querySelector<HTMLInputElement>('.receipt-qty input')!;expect(disabled.getAttribute('aria-disabled')).toBe('true');expect(disabled.disabled).toBe(true);resolveOptions(response(api.preview.mock.calls[0][1]));await flushPromises();let ready=document.querySelector<HTMLInputElement>('.receipt-qty input')!;expect(ready.getAttribute('aria-disabled')).toBe('false');expect(ready.disabled).toBe(false);expect(ready.readOnly).toBe(false);await fillCommon();await fillActual(0,6);await click('保存实际收样');expect(document.body.textContent).toContain('暂时无法保存');ready=document.querySelector<HTMLInputElement>('.receipt-qty input')!;expect(ready.getAttribute('aria-disabled')).toBe('false');expect(ready.disabled).toBe(false);expect(ready.value).toBe('6');await click('保存实际收样');expect(api.save.mock.calls[1][1]).toEqual(api.save.mock.calls[0][1]);expect(document.body.textContent).toContain('实际收样已保存')})

 it('reuses the same true sample code across separate actual receipts and carries the supplier shipment identity',async()=>{api.candidates.mockResolvedValue({content:[{sampleIdentityId:71,sampleCode:'SY-000071',name:'款式1',specName:'iPhone18灰色'}],canCreate:true,requiresReview:false});api.save.mockImplementation(async(_id,input)=>({...saved(input),batches:saved(input).batches.map(batch=>({...batch,sampleIdentityId:71,sampleCode:'SY-000071'}))}));await start([style(1)]);await wrapper.setProps({supplierShipmentId:9});await fillCommon();await fillActual(0,3);await click('保存实际收样');expect(api.save.mock.calls[0][1]).toMatchObject({supplierShipmentId:9,rows:[expect.objectContaining({sampleIdentityId:71,qty:3})]});expect(document.body.textContent).toContain('SY-000071');wrapper.unmount();await start([style(1)]);await wrapper.setProps({supplierShipmentId:9});await fillCommon();await fillActual(0,2);await click('保存实际收样');expect(api.save.mock.calls[1][1]).toMatchObject({supplierShipmentId:9,rows:[expect.objectContaining({sampleIdentityId:71,qty:2})]});expect(api.save.mock.calls[1][1].requestId).not.toBe(api.save.mock.calls[0][1].requestId)})

})


describe('inline batch actual receipt entry',()=>{
 it('fills three checked styles locally once and retains individual edits in the original single save',async()=>{
  await start([style(1),style(2),style(3)]);await fillCommon();
  expect(rowChecks().map(input=>input.props('modelValue'))).toEqual([true,true,true]);
  expect(allCheck().props('modelValue')).toBe(true);expect(bulkButton().textContent).toContain('（3）');
  const reads=api.preview.mock.calls.length;await fillBulk('黑色',2);await applyBulk();
  expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2,2]);
  expect(wrapper.findAllComponents({name:'ElAutocomplete'}).map(input=>input.props('modelValue'))).toEqual(['黑色','黑色','黑色']);
  expect(document.body.textContent).toContain('合计 6 件');expect(api.preview).toHaveBeenCalledTimes(reads);expect(api.save).not.toHaveBeenCalled();expect(api.candidates).not.toHaveBeenCalled();
  const models=wrapper.findAllComponents(ElSelect).filter(input=>String(input.props('ariaLabel')||'').endsWith('实收型号'));
  expect(models.map(input=>input.props('modelValue'))).toEqual([undefined,undefined,undefined]);
  models.forEach(model=>model.vm.$emit('update:modelValue',18));await typeColor(1,'白色');actualQuantities()[1].vm.$emit('update:modelValue',3);await flushPromises();
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
  const models=wrapper.findAllComponents(ElSelect).filter(input=>String(input.props('ariaLabel')||'').endsWith('实收型号'));models.forEach(model=>model.vm.$emit('update:modelValue',18));
  wrapper.findAllComponents({name:'ElAutocomplete'})[0].vm.$emit('select',{id:8,name:'黑色',value:'黑色'});wrapper.findAllComponents({name:'ElAutocomplete'})[1].vm.$emit('select',{id:88,name:'黑色',value:'黑色'});await flushPromises();
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
  await start();const reads=api.preview.mock.calls.length;await applyBulk();expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([undefined,undefined]);
  await fillBulk('黑色',2);allCheck().vm.$emit('change',false);await flushPromises();expect(bulkButton().disabled).toBe(true);await applyBulk();
  expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([undefined,undefined]);
  allCheck().vm.$emit('change',true);await flushPromises();
  for(const invalid of [0,-1,1.5,Number.MAX_SAFE_INTEGER+1]){bulkQty().vm.$emit('update:modelValue',invalid);bulkButton().click();await flushPromises();expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([undefined,undefined])}
  expect(api.preview).toHaveBeenCalledTimes(reads);expect(api.save).not.toHaveBeenCalled();
 })
 it('leaves added specifications unchecked until explicitly selected and removes their selection when excluded',async()=>{
  await start();const first=document.querySelector('.receipt-row')!;[...first.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim()==='增加规格')!.click();await flushPromises();
  expect(rowChecks().map(input=>input.props('modelValue'))).toEqual([true,true,false]);expect(allCheck().props('indeterminate')).toBe(true);
  await fillBulk('黑色',2);await applyBulk();expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2,undefined]);
  expect(wrapper.findAllComponents({name:'ElAutocomplete'})[2].props('modelValue')).toBe('');
  await checkLine(2,true);const extra=[...document.querySelectorAll('.receipt-row')][2];[...extra.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim()==='本次不收')!.click();await flushPromises();
  expect(rowChecks()).toHaveLength(2);expect(allCheck().props('modelValue')).toBe(true);expect(bulkButton().textContent).toContain('（2）');
 })
 it('keeps batch controls read-only during loading and saving and restores their native quantity input',async()=>{
  let resolveOptions!:(value:ReturnType<typeof response>)=>void;api.preview.mockImplementationOnce(()=>new Promise(resolve=>{resolveOptions=resolve}));await start();
  const input=bulkQty().get('input');expect(input.attributes('aria-disabled')).toBe('true');expect((input.element as HTMLInputElement).disabled).toBe(true);expect(bulkColor().props('disabled')).toBe(true);expect(rowChecks().every(input=>input.props('disabled'))).toBe(true);expect(bulkButton().disabled).toBe(true);
  resolveOptions(response(api.preview.mock.calls[0][1]));await flushPromises();const ready=bulkQty().get('input');expect(ready.attributes('aria-disabled')).toBe('false');expect((ready.element as HTMLInputElement).disabled).toBe(false);expect(bulkColor().props('disabled')).toBe(false);
  await fillCommon();await fillActual(0,3);await fillActual(1,4);await fillBulk('黑色',2);await applyBulk();
  let resolveSave!:(value:SelectionReceiptResult)=>void;api.save.mockImplementationOnce(()=>new Promise(resolve=>{resolveSave=resolve}));await click('保存实际收样');
  const submitted=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));expect(bulkColor().props('disabled')).toBe(true);expect(bulkQty().get('input').attributes('aria-disabled')).toBe('true');expect(bulkButton().disabled).toBe(true);
  bulkColor().vm.$emit('update:modelValue','不可覆盖');bulkQty().vm.$emit('update:modelValue',9);bulkButton().click();await flushPromises();
  expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2]);expect(wrapper.findAllComponents({name:'ElAutocomplete'}).map(input=>input.props('modelValue'))).toEqual(['黑色','黑色']);expect(api.save).toHaveBeenCalledTimes(1);
  resolveSave(saved(submitted));await flushPromises();expect(document.body.textContent).toContain('实际收样已保存');
 })
 it('replays an unknown multi-style save exactly even if batch widgets receive stale change events',async()=>{
  api.save.mockRejectedValueOnce({code:'ECONNABORTED'});await start();await fillCommon();await fillActual(0,3);await fillActual(1,4);await fillBulk('黑色',2);await applyBulk();await click('保存实际收样');
  const first=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));wrapper.unmount();await start();expect(document.body.textContent).toContain('上次保存结果尚未核对');
  expect(bulkColor().props('disabled')).toBe(true);expect(bulkQty().props('disabled')).toBe(true);expect(bulkButton().disabled).toBe(true);
  bulkColor().vm.$emit('update:modelValue','不可覆盖');bulkQty().vm.$emit('update:modelValue',9);bulkButton().click();await flushPromises();
  expect(wrapper.findAllComponents({name:'ElAutocomplete'}).map(input=>input.props('modelValue'))).toEqual(['黑色','黑色']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([2,2]);
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
  await start();await fillCommon();await chooseAllPhones();await addActualLine(0);models()[2].vm.$emit('update:modelValue',17);await flushPromises();await checkLine(1,false);await checkLine(2,true);await fillTwoColours();await applyBulk();
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(5);const untouchedIndex=[...document.querySelectorAll('.receipt-row')].findIndex(row=>row.textContent?.includes('款式2'));expect(untouchedIndex).toBeGreaterThanOrEqual(0);expect(actualQuantities()[untouchedIndex].props('modelValue')).toBeUndefined();
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
  await start();await chooseAllPhones();await fillTwoColours('Black',2,' black ',2);const reads=api.preview.mock.calls.length;await applyBulk();expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['','']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([undefined,undefined]);expect(document.body.textContent).toContain('颜色重复');
  await fillBulk('',2,1);await applyBulk();expect(document.querySelectorAll('.receipt-row')).toHaveLength(2);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['','']);expect(document.body.textContent).toContain('逐行填写颜色名称');
  await fillBulk('White',2,1);bulkQty(1).vm.$emit('update:modelValue',0);bulkButton().click();await flushPromises();
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(2);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([undefined,undefined]);expect(api.preview).toHaveBeenCalledTimes(reads);expect(api.save).not.toHaveBeenCalled();
 })
 it('requires each selected phone explicitly for multiple colours and recovers without guessing one',async()=>{
  await start();models()[0].vm.$emit('update:modelValue',18);await flushPromises();await fillTwoColours();await applyBulk();
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(2);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['','']);expect(models().map(model=>model.props('modelValue'))).toEqual([18,undefined]);expect(document.body.textContent).toContain('实收型号');
  models()[1].vm.$emit('update:modelValue',17);await flushPromises();await applyBulk();expect(document.querySelectorAll('.receipt-row')).toHaveLength(4);expect(models().map(model=>model.props('modelValue'))).toEqual([18,18,17,17]);expect(api.save).not.toHaveBeenCalled();
 })
 it('cancels a filled multi-colour local form without saving or carrying generated rows into the next receipt',async()=>{
  await start([style(1),style(2),style(3)]);await chooseAllPhones();await fillTwoColours();await applyBulk();expect(document.querySelectorAll('.receipt-row')).toHaveLength(6);await click('取消');
  expect(wrapper.emitted('close')).toHaveLength(1);expect(api.save).not.toHaveBeenCalled();expect(sessionStorage.getItem('plm:selection-receipt:71:7')).toBeNull();wrapper.unmount();await start([style(1),style(2),style(3)]);
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(3);expect(document.querySelectorAll('.receipt-bulk-line')).toHaveLength(1);expect(colorInputs().map(input=>input.props('modelValue'))).toEqual(['','','']);expect(actualQuantities().map(input=>input.props('modelValue'))).toEqual([undefined,undefined,undefined]);
 })
 it('locks and exactly replays a pending six-specification save without generating extra rows',async()=>{
  api.save.mockRejectedValueOnce({code:'ECONNABORTED'});await start([style(1),style(2),style(3)]);await fillCommon();await chooseAllPhones();await fillTwoColours();await applyBulk();await click('保存实际收样');
  const first=JSON.parse(JSON.stringify(api.save.mock.calls[0][1]));expect(first.rows).toHaveLength(6);wrapper.unmount();await start([style(1),style(2),style(3)]);expect(document.querySelectorAll('.receipt-row')).toHaveLength(6);
  expect(bulkColor().props('disabled')).toBe(true);expect(bulkQty().props('disabled')).toBe(true);const add=[...document.querySelectorAll<HTMLButtonElement>('button')].find(button=>button.textContent?.trim()==='增加颜色')!;expect(add.disabled).toBe(true);add.click();bulkColor().vm.$emit('update:modelValue','不可覆盖');bulkQty().vm.$emit('update:modelValue',9);bulkButton().click();await flushPromises();
  expect(document.querySelectorAll('.receipt-row')).toHaveLength(6);expect(document.querySelectorAll('.receipt-bulk-line')).toHaveLength(1);const reads=api.preview.mock.calls.length;await click('核对本次保存结果');expect(api.save.mock.calls[1][1]).toEqual(first);expect(api.preview).toHaveBeenCalledTimes(reads);expect(sessionStorage.getItem('plm:selection-receipt:71:7')).toBeNull();
 })
})
