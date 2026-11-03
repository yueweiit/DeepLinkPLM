import {DOMWrapper,flushPromises,mount,type VueWrapper} from '@vue/test-utils'
import {computed,defineComponent,ref} from 'vue'
import {createPinia,setActivePinia} from 'pinia'
import ElementPlus from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
import {useUserStore} from '@/stores/user'
import type {CatalogStyle} from '@/types/partnerCatalog'
import type {CompatiblePhone} from '@/types/molds'
import Dialog from '../catalog/CatalogSelectionDialog.vue'

const api=vi.hoisted(()=>({preview:vi.fn(),save:vi.fn(),options:vi.fn(),models:vi.fn(),brands:vi.fn()}))
vi.mock('@/api/modules/partnerCatalog',()=>({previewCatalogSelection:api.preview,addCatalogSelection:api.save}))
vi.mock('@/api/modules/phoneReference',()=>({getPhoneModelOptions:api.options,getPhoneModels:api.models,getPhoneSelectionBrands:api.brands}))
const phones:CompatiblePhone[]=[5018,5017,5016,5015].map((id,index)=>({id,phoneModelId:id,brandId:1,brandName:'Apple',modelName:index?'iPhone '+(18-index):'iPhone18ProMax',phoneModelCode:String(1218-index),codeItemId:300+index,aliases:[],searchTexts:['iPhone',String(1218-index)],codeLinkStatus:'linked',status:'enabled'}))
const styles=():CatalogStyle[]=>Array.from({length:6},(_,index)=>({sourceKind:'quote',recordId:72,rowId:`00000000-0000-4000-8000-${String(index+1).padStart(12,'0')}`,rowVersion:5,recordVersion:8,sourceCode:'SQ-72',sourceName:'隔离供方资料',supplierCode:'QA-SUP',supplierName:'隔离供方',name:'隔离款式'+(index+1),phoneScopeMode:'supplier',phoneModelIds:phones.map(phone=>phone.phoneModelId),phones:phones.map(phone=>({...phone})),color:'透明、黑色',refs:[],fileDetails:[]}))
const result={selection:{id:90,name:'隔离选品目录',version:1},rowIds:['new'],addedCount:6,reusedCount:0,replayed:false}
const cancelledHistory=()=>{const message='已有选品中的型号已取消或排除，请在原选品明确处理，不能静默恢复';const items=styles().map((item,index)=>({...item,phoneModelIds:[5018],requiresReselect:true,cancelledMatches:[{rowId:'old-cancelled-'+index,rowVersion:4,phoneModelIds:[5018,5017],overlappingPhoneModelIds:[5018],discarded:true,selected:false,confirmed:true}],messages:[message]}));return{canAdd:false,previewToken:'blocked-history',items,messages:items.map(item=>item.name+'：'+message)}}
let wrapper:VueWrapper
const screen=()=>new DOMWrapper(document.body)
const row=(index=0)=>screen().findAll('.selection-model-row')[index]
const button=(text:string)=>screen().findAll('button').find(node=>node.text()===text)!
async function click(text:string){expect(button(text),text).toBeTruthy();await button(text).trigger('click');await flushPromises()}
function deferred(){let resolve!:(value:unknown)=>void;const promise=new Promise(done=>{resolve=done});return{promise,resolve}}
beforeEach(()=>{
 vi.resetAllMocks();setActivePinia(createPinia());const user=useUserStore();user.token='isolated-catalog-session';user.profile={userId:1,roleCode:'engineer'} as never;user.permissions=['partner:view','samples:write']
 api.preview.mockImplementation(async input=>({canAdd:true,previewToken:'qa-token',items:input.items.map((item:unknown)=>({...item as object,name:'隔离款式',messages:[]})),messages:[]}));api.save.mockResolvedValue(result)
 api.options.mockImplementation(async(ids:number[])=>phones.filter(phone=>ids.includes(phone.phoneModelId)));api.models.mockResolvedValue({content:phones,totalElements:phones.length});api.brands.mockResolvedValue([{id:1,brandId:1,name:'Apple'}])
})
afterEach(()=>{wrapper?.unmount();document.body.innerHTML='';vi.restoreAllMocks()})
async function start(cloneOnBusy=false){
 const Host=defineComponent({components:{Dialog},emits:['added'],setup(){const open=ref(false),items=ref(styles()),busy=ref(false);const rendered=computed(()=>{if(cloneOnBusy){void busy.value;return items.value.map(item=>({...item}))}return items.value});return{open,items,busy,rendered,replace(){items.value=JSON.parse(JSON.stringify(items.value))},changeVersion(){items.value=items.value.map(item=>({...item,recordVersion:item.recordVersion+1}))},changeScope(){items.value=items.value.map(item=>({...item,phoneModelIds:item.phoneModelIds.filter(id=>id!==5018)}))},disablePhone(){items.value=items.value.map(item=>({...item,phones:item.phones.map(phone=>({...phone,status:phone.phoneModelId===5018?'disabled':phone.status}))}))},reorder(){items.value=[...items.value].reverse().map(item=>({...item,phones:[...item.phones].reverse()}))}}},template:'<Dialog v-model="open" supplier-code="QA-SUP" supplier-name="隔离供方" :items="rendered" :can-write="true" @busy="busy=$event" @added="$emit(\'added\',$event)"/>'})
 wrapper=mount(Host,{attachTo:document.body,global:{plugins:[ElementPlus]}});(wrapper.vm as unknown as {open:boolean}).open=true;await flushPromises()
}
async function chooseModels(all=false){
 await screen().get('button[aria-label="批量选择系列／型号"]').trigger('click');await flushPromises()
 const check=all?screen().get<HTMLInputElement>('.phone-model-panel .results-toolbar input'):screen().findAll('.phone-model-panel .model-checkbox').find(node=>node.text().includes('iPhone18ProMax'))!.get<HTMLInputElement>('input')
 check.element.click();await flushPromises();const done=screen().findAll('button').find(node=>node.text().startsWith('完成选择（'))!;await done.trigger('click');await flushPromises();await click('应用到 6 款')
}
async function native(input:Pick<DOMWrapper<HTMLInputElement>,'element'|'trigger'>,value:string,blur=true){input.element.focus();input.element.value=value;await input.trigger('input');if(blur){await input.trigger('change');await input.trigger('blur')}await flushPromises()}
async function prepareSix(){
 await chooseModels();const colors=screen().get<HTMLInputElement>('input[aria-label="批量选样颜色"]');colors.element.focus();await colors.trigger('click');await flushPromises()
 for(const name of ['透明','黑色']){const option=[...document.querySelectorAll<HTMLElement>('[role="option"]')].find(node=>node.textContent?.trim()===name)!;expect(option,name).toBeTruthy();option.click();await flushPromises()}
 await colors.trigger('keydown',{key:'Escape'});await click('应用颜色')
 await native(screen().get<HTMLInputElement>('input[aria-label="每款统一拟拿样数量"]'),'2');await click('应用每款数量')
 expect(screen().get('.selection-total').text()).toContain('已选 6 款');expect(screen().get('.selection-total').text()).toContain('拟拿样合计 12 件')
 for(const item of screen().findAll('.selection-model-row'))expect(item.get<HTMLInputElement>('.selection-row-quantity input').element.value).toBe('2')
}
function assertSaved(){expect(api.save).toHaveBeenCalledTimes(1);const input=api.save.mock.calls[0][0];expect(input.items).toHaveLength(6);for(const item of input.items)expect(item).toMatchObject({phoneModelIds:[5018],sampleColors:['透明','黑色'],sampleQty:2});expect(JSON.stringify(input.items)).not.toContain('1218');expect(wrapper.emitted('added')).toHaveLength(1)}

describe('catalog selection with real phone, colour and numeric components',()=>{
 it('uses the count and adjustment controls to open and close the same row without changing its selection',async()=>{
  await start();await chooseModels(true);const count=row().findAll('button').find(node=>node.text().includes('个，展开'))!;await count.trigger('click');await flushPromises();expect(row().get('.selection-adjust-button').attributes('aria-expanded')).toBe('true')
  expect(count.text()).toContain('收起');await count.trigger('click');await flushPromises();expect(row().get('.selection-adjust-button').attributes('aria-expanded')).toBe('false')
  await row().get('.selection-adjust-button').trigger('click');await flushPromises();expect(count.attributes('aria-expanded')).toBe('true');await count.trigger('click');await flushPromises();expect(row().get('.selection-adjust-button').attributes('aria-expanded')).toBe('false');expect(row().text()).toContain('本次选 4 个')
 })
 it('saves six styles using the true ID rather than displayed code 1218 when the last native quantity input is still focused',async()=>{
  const pending=deferred();api.preview.mockReturnValue(pending.promise);await start();await prepareSix();const last=row(5).get<HTMLInputElement>('.selection-row-quantity input');await native(last,'2',false);expect(document.activeElement).toBe(last.element)
  await click('保存选样');expect(api.preview).toHaveBeenCalledTimes(1);for(const input of screen().findAll<HTMLInputElement>('.selection-row-quantity input'))expect(input.element.disabled).toBe(true)
  pending.resolve({canAdd:true,previewToken:'qa-token',items:[],messages:[]});await flushPromises();assertSaved()
 })
 it('does not discard a valid preview when the parent busy state renders equal source objects again',async()=>{
  const pending=deferred();api.preview.mockReturnValue(pending.promise);await start(true);await prepareSix();await click('保存选样');expect((wrapper.vm as unknown as {busy:boolean}).busy).toBe(true)
  pending.resolve({canAdd:true,previewToken:'qa-token',items:[],messages:[]});await flushPromises();assertSaved()
 })
 it.each(['preview','save'] as const)('retains a %s response across a same-value source array replacement',async phase=>{
  const pending=deferred();api[phase].mockReturnValue(pending.promise);await start();await prepareSix();await click('保存选样');(wrapper.vm as unknown as {replace:()=>void}).replace();await flushPromises();pending.resolve(phase==='preview'?{canAdd:true,previewToken:'qa-token',items:[],messages:[]}:result);await flushPromises();assertSaved()
 })
 it.each(['changeVersion','changeScope','disablePhone'])('keeps a real %s from accepting an old preview and makes the interruption visible at the fixed footer',async method=>{
  const pending=deferred();api.preview.mockReturnValue(pending.promise);await start();await prepareSix();await click('保存选样');(wrapper.vm as unknown as Record<string,()=>void>)[method]();await flushPromises();pending.resolve({canAdd:true,previewToken:'old-token',items:[],messages:[]});await flushPromises()
  expect(api.save).not.toHaveBeenCalled();expect(screen().get('.el-dialog__footer [role="alert"]').text()).toContain('变化');expect(row(5).get<HTMLInputElement>('.selection-row-quantity input').element.value).toBe('2')
 })
 it('shows a real preview rejection next to save in the fixed footer while preserving all six native choices',async()=>{
  api.preview.mockResolvedValue({canAdd:false,previewToken:'',items:[{...styles()[5],name:'隔离款式6',messages:['来源版本已改变，请重新核对']}],messages:[]});await start();await prepareSix();await click('保存选样')
  expect(api.save).not.toHaveBeenCalled();expect(screen().get('.el-dialog__footer [role="alert"]').text()).toContain('隔离款式6：来源版本已改变');expect(row(5).get<HTMLInputElement>('.selection-row-quantity input').element.value).toBe('2');expect(row(5).text()).toContain('iPhone18ProMax');await click('定位款式 · 隔离款式6');expect(document.activeElement).toBe(row(5).element);expect(row(5).get('.selection-adjust-button').attributes('aria-expanded')).toBe('true')
 })
 it('accepts the same selection when a parent reorders source rows and phone display members during preview',async()=>{
  const pending=deferred();api.preview.mockReturnValue(pending.promise);await start();await prepareSix();await click('保存选样');(wrapper.vm as unknown as {reorder:()=>void}).reorder();await flushPromises();pending.resolve({canAdd:true,previewToken:'qa-token',items:[],messages:[]});await flushPromises();assertSaved()
 })
 it('keeps an addition error beside save and retries with all six choices preserved',async()=>{
  api.save.mockRejectedValueOnce(new Error('来源或目标已变化，请重新核对'));await start();await prepareSix();await click('保存选样');expect(screen().get('.el-dialog__footer [role="alert"]').text()).toContain('来源或目标已变化');expect(row(5).get<HTMLInputElement>('.selection-row-quantity input').element.value).toBe('2');await click('保存选样');expect(api.preview).toHaveBeenCalledTimes(2);expect(api.save).toHaveBeenCalledTimes(2);expect(wrapper.emitted('added')).toHaveLength(1)
 })
 it('makes a malformed success preview without a token explicit instead of leaving save apparently idle',async()=>{
  api.preview.mockResolvedValue({canAdd:true,previewToken:'',items:[],messages:[]});await start();await prepareSix();await click('保存选样');expect(api.save).not.toHaveBeenCalled();expect(screen().get('.el-dialog__footer [role="alert"]').text()).toContain('保存凭证');expect(row(5).get<HTMLInputElement>('.selection-row-quantity input').element.value).toBe('2')
 })

 it('deduplicates the six cancelled-history rejections repeated in preview summary and per-style messages',async()=>{
  const message='已有选品中的型号已取消或排除，请在原选品明确处理，不能静默恢复'
  const items=styles().map(item=>({...item,messages:[message]}))
  api.preview.mockResolvedValue({canAdd:false,previewToken:'blocked-history',items,messages:items.map(item=>item.name+'：'+message)})
  await start();await prepareSix();await click('保存选样')
  expect(api.save).not.toHaveBeenCalled();const issues=screen().findAll('.el-dialog__footer .selection-issue');expect(issues).toHaveLength(6);expect(new Set(issues.map(item=>item.text())).size).toBe(6)
  expect(screen().findAll('.el-dialog__footer button').filter(item=>item.text().startsWith('定位款式'))).toHaveLength(6)
  expect(screen().get('.selection-total').text()).toContain('拟拿样合计 12 件')
 })

 it('writes only the six current specifications after an explicit reselection and leaves cancelled multi-model history intact',async()=>{
  const history=cancelledHistory(),original=JSON.stringify(history);api.preview.mockResolvedValueOnce(history);await start();await prepareSix();await click('保存选样')
  expect(api.save).not.toHaveBeenCalled();expect(api.preview.mock.calls[0][0].items.every((item:{reselectCancelled?:boolean})=>item.reselectCancelled===undefined)).toBe(true)
  expect(screen().get('.el-dialog__footer').text()).toContain('以前的选样已取消，重新选择将保留历史，仅保存本次规格');await click('重新选择本次规格');assertSaved()
  expect(api.preview).toHaveBeenCalledTimes(2);for(const item of api.save.mock.calls[0][0].items){expect(item.reselectCancelled).toBe(true);expect(item).not.toHaveProperty('cancelledMatches');expect(item.phoneModelIds).not.toContain(5017)}
  expect(JSON.stringify(history)).toBe(original);expect(api.save.mock.calls[0][0].items.reduce((sum:number,item:{sampleQty:number})=>sum+item.sampleQty,0)).toBe(12)
 })
 it('keeps an explicit reselection after a preview failure without writing until a fresh preview succeeds',async()=>{
  api.preview.mockResolvedValueOnce(cancelledHistory()).mockRejectedValueOnce(new Error('来源版本已变化，请重新核对'))
  await start();await prepareSix();await click('保存选样');await click('重新选择本次规格');expect(api.save).not.toHaveBeenCalled();expect(screen().get('.el-dialog__footer [role="alert"]').text()).toContain('来源版本已变化');expect(row(5).get<HTMLInputElement>('.selection-row-quantity input').element.value).toBe('2')
  await click('保存选样');assertSaved();expect(api.preview.mock.calls[2][0].items.every((item:{reselectCancelled?:boolean})=>item.reselectCancelled===true)).toBe(true)
 })
 it('invalidates a true source version change while explicit reselection is being previewed',async()=>{
  const pending=deferred(),changed=cancelledHistory();changed.items.forEach(item=>item.recordVersion=9);api.preview.mockResolvedValueOnce(cancelledHistory()).mockReturnValueOnce(pending.promise).mockResolvedValueOnce(changed)
  await start();await prepareSix();await click('保存选样');await button('重新选择本次规格').trigger('click');await flushPromises();(wrapper.vm as unknown as {changeVersion:()=>void}).changeVersion();await flushPromises();pending.resolve({canAdd:true,previewToken:'old-reselection',items:[],messages:[]});await flushPromises()
  expect(api.save).not.toHaveBeenCalled();expect(screen().get('.el-dialog__footer [role="alert"]').text()).toContain('变化');await click('保存选样');expect(api.save).not.toHaveBeenCalled();expect(api.preview.mock.calls[2][0].items.every((item:{reselectCancelled?:boolean})=>item.reselectCancelled===undefined)).toBe(true);expect(button('重新选择本次规格')).toBeTruthy()
 })
 it('does not offer a historical reselection bypass for an active confirmed selection',async()=>{
  const denied=cancelledHistory();denied.items=denied.items.map(item=>({...item,requiresReselect:false,cancelledMatches:[],messages:['该款选样依据已保留，请先修改选样再调整拟拿样数量']}));denied.messages=denied.items.map(item=>item.name+'：'+item.messages[0]);api.preview.mockResolvedValueOnce(denied)
  await start();await prepareSix();await click('保存选样');expect(api.save).not.toHaveBeenCalled();expect(button('重新选择本次规格')).toBeUndefined();expect(screen().get('.el-dialog__footer [role="alert"]').text()).toContain('选样依据已保留')
 })
 it('keeps other confirmed-row protection after explicitly reselecting only the cancelled styles',async()=>{
  const denied=cancelledHistory();denied.items[0].requiresReselect=false;denied.items[0].cancelledMatches=[];denied.items[0].messages=['活动选样已确认，不能覆盖'];denied.messages=denied.items.map(item=>item.name+'：'+item.messages[0]);api.preview.mockResolvedValueOnce(denied).mockResolvedValueOnce({canAdd:false,previewToken:'still-protected',items:[denied.items[0]],messages:['隔离款式1：活动选样已确认，不能覆盖']})
  await start();await prepareSix();await click('保存选样');await click('重新选择本次规格');expect(api.save).not.toHaveBeenCalled();const items=api.preview.mock.calls[1][0].items;expect(items[0].reselectCancelled).toBeUndefined();expect(items.slice(1).every((item:{reselectCancelled?:boolean})=>item.reselectCancelled===true)).toBe(true);expect(screen().get('.el-dialog__footer [role="alert"]').text()).toContain('活动选样已确认')
 })

 it('replays the exact explicit reselection after an unknown write result without another preview or intent action',async()=>{
  api.preview.mockResolvedValueOnce(cancelledHistory());api.save.mockRejectedValueOnce(Object.assign(new Error('保存超时'),{code:'ETIMEDOUT'})).mockResolvedValueOnce(result)
  await start();await prepareSix();await click('保存选样');await click('重新选择本次规格');expect(api.save).toHaveBeenCalledTimes(1);const original=JSON.parse(JSON.stringify(api.save.mock.calls[0][0]));expect(original.items.every((item:{reselectCancelled?:boolean})=>item.reselectCancelled===true)).toBe(true)
  expect(screen().get('.el-dialog__footer [role="alert"]').text()).toContain('保存结果待核对');expect(button('重新选择本次规格')).toBeUndefined();await click('核对保存结果');expect(api.preview).toHaveBeenCalledTimes(2);expect(api.save).toHaveBeenCalledTimes(2);expect(api.save.mock.calls[1][0]).toEqual(original);expect(wrapper.emitted('added')).toHaveLength(1)
 })

})
