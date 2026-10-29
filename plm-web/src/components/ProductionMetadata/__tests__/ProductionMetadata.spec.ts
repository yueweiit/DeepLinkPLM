import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import ElementPlus,{ElButton,ElCheckbox,ElInput,ElSelect} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
const api=vi.hoisted(()=>({saveProductionMetadata:vi.fn(),getProductionMetadataOptions:vi.fn(),getProductProductionStatus:vi.fn(),getSalesCatalog:vi.fn(),saveSalesProduct:vi.fn()}))
vi.mock('@/api/modules/productionMetadata',()=>api)
import MetadataDialog from '../MetadataDialog.vue'
import ProductProductionControl from '../ProductProductionControl.vue'
import ProductProductionList from '../ProductProductionList.vue'
import ProductionStatusFilter from '../ProductionStatusFilter.vue'
import ProductSalesCatalog from '../ProductSalesCatalog.vue'
import type {SalesProduct} from '@/api/modules/productionMetadata'
import {useUserStore} from '@/stores/user'
import {useCollaborationWorkspace} from '@/stores/collaborationWorkspace'
import {useProductionMetadataStore} from '@/stores/productionMetadata'
import {comparePhoneCodes,productionStatusName} from '@/utils/productionStatus'
let w:VueWrapper|undefined
beforeEach(()=>{vi.clearAllMocks();api.getSalesCatalog.mockReset();api.saveSalesProduct.mockReset();setActivePinia(createPinia());useUserStore().token='production-test';api.getProductionMetadataOptions.mockResolvedValue({canMaintain:true});api.getProductProductionStatus.mockResolvedValue('unknown');api.saveProductionMetadata.mockResolvedValue([{id:1,productionStatus:'active'}])})
afterEach(()=>{w?.unmount();w=undefined})
async function dialog(items:any[]=[{id:1,label:'0001 · 完整型号名称',productionStatus:'unknown'}],kind:'phone'|'product'='product'){w=mount(MetadataDialog,{props:{modelValue:true,items,kind,brands:[{id:3,brandId:3,name:'确认品牌',status:'enabled'}]},global:{plugins:[ElementPlus]}});await flushPromises();return w}
async function reason(){w!.getComponent(ElInput).vm.$emit('update:modelValue','人工核对生产状态');await flushPromises()}
async function save(){await w!.findAllComponents(ElButton).find(b=>b.text()==='保存生产标识')!.trigger('click');await flushPromises()}
describe('independent production metadata',()=>{
  it('sends only changed brand fields so a different production-field update can merge safely',async()=>{const v=await dialog([{id:1,productionStatus:'active',brandIds:[1],memberBrandIds:[1]}],'phone');v.getComponent(ElCheckbox).vm.$emit('update:modelValue',true);await flushPromises();v.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue',[1,3]);await reason();await save();expect(api.saveProductionMetadata).toHaveBeenCalledWith('phone',[{id:1,changes:{brandIds:[1,3]},original:{brandIds:[1]}}],'人工核对生产状态')})
  it('filters the independent production field using exactly the three business states',async()=>{w=mount(ProductionStatusFilter,{props:{modelValue:''}});expect(w.findAll('option').map(o=>o.text())).toEqual(['全部生产状态','待确认','在用','已停产']);await w.get('select').setValue('discontinued');expect(w.emitted('update:modelValue')).toEqual([['discontinued']]);expect(w.emitted('change')).toHaveLength(1)})
  it('retains full product fields and selects only explicitly checked rows for batch maintenance',async()=>{const rows=[{productId:1,productName:'完整产品名称',productCode:'NFA4020',status:'archived' as const,productionStatus:'unknown' as const,seriesName:'骑士',versionNo:'A',activeBomVersion:'B',completionRate:1},{productId:2,productName:'不同生产状态产品',productCode:'NFB4020',status:'archived' as const,productionStatus:'discontinued' as const}];w=mount(ProductProductionList,{props:{rows,selectable:true},global:{plugins:[ElementPlus],stubs:{FixedTableViewport:{template:'<div><slot /></div>'}}}});await flushPromises();expect(w.text()).toContain('BOM：B');expect(w.text()).toContain('完整率：100%');expect(w.text()).toContain('NFA4020');expect(w.text()).toContain('已归档');expect(w.text()).toContain('已停产');await w.get('input[aria-label="选择产品 完整产品名称"]').setValue(true);expect(w.emitted('selectionChange')?.at(-1)).toEqual([[rows[0]]]);await w.setProps({rows:[rows[1]!]});expect(w.emitted('selectionChange')?.at(-1)).toEqual([[]])})
  it('requires a reason and sends the current original without changing development state',async()=>{const v=await dialog();expect(v.findAllComponents(ElButton).find(b=>b.text()==='保存生产标识')!.props('disabled')).toBe(true);v.getComponent(ElSelect).vm.$emit('update:modelValue','active');await reason();await save();expect(api.saveProductionMetadata).toHaveBeenCalledWith('product',[{id:1,changes:{productionStatus:'active'},original:{productionStatus:'unknown'}}],'人工核对生产状态');expect(v.emitted('saved')).toHaveLength(1);expect(useCollaborationWorkspace().revision).toBe(1)})
  it('keeps the form open on a conflict and never reports partial batch success',async()=>{api.saveProductionMetadata.mockRejectedValue(new Error('状态已被修改，本批已回滚'));const v=await dialog();v.getComponent(ElSelect).vm.$emit('update:modelValue','active');await reason();await save();expect(v.findAllComponents({name:'ElAlert'}).some(a=>a.props('title').includes('本批已回滚'))).toBe(true);expect(v.emitted('saved')).toBeUndefined();expect(v.emitted('update:modelValue')).toBeUndefined();expect(useCollaborationWorkspace().revision).toBe(0)})
  it('keeps each group’s own member brands when batch replacing manual classification',async()=>{const v=await dialog([{id:1,productionStatus:'unknown',brandIds:[1,4],memberBrandIds:[1]},{id:2,productionStatus:'discontinued',brandIds:[2],memberBrandIds:[2]}],'phone');v.getComponent(ElCheckbox).vm.$emit('update:modelValue',true);await flushPromises();v.findAllComponents(ElSelect)[1]!.vm.$emit('update:modelValue',[3]);await reason();await save();expect(api.saveProductionMetadata).toHaveBeenCalledWith('phone',[{id:1,changes:{brandIds:[1,3]},original:{brandIds:[1,4]}},{id:2,changes:{brandIds:[2,3]},original:{brandIds:[2]}}],'人工核对生产状态')})
  it('ignores a late response after the editor was closed and reopened for the same IDs',async()=>{let resolve!:(rows:any[])=>void;api.saveProductionMetadata.mockImplementation(()=>new Promise(r=>resolve=r));const v=await dialog();v.getComponent(ElSelect).vm.$emit('update:modelValue','active');await reason();await save();await v.setProps({modelValue:false});await v.setProps({modelValue:true});resolve([{id:1,productionStatus:'active'}]);await flushPromises();expect(v.emitted('saved')).toBeUndefined();expect(useCollaborationWorkspace().revision).toBe(0)})
  it('invalidates pending saves and cached maintenance rights on an identity change',async()=>{let resolve!:(rows:any[])=>void;api.saveProductionMetadata.mockImplementation(()=>new Promise(r=>resolve=r));const v=await dialog();v.getComponent(ElSelect).vm.$emit('update:modelValue','active');await reason();await save();await useProductionMetadataStore().ensure();expect(useProductionMetadataStore().canMaintain).toBe(true);useUserStore().token='different-login';await flushPromises();resolve([{id:1,productionStatus:'active'}]);await flushPromises();expect(v.emitted('saved')).toBeUndefined();expect(useProductionMetadataStore().canMaintain).toBe(false)})
  it('shows frozen or archived product metadata independently and guards stale detail reads',async()=>{let resolve!:(state:'discontinued')=>void;api.getProductProductionStatus.mockImplementation(()=>new Promise(r=>resolve=r));w=mount(ProductProductionControl,{props:{productId:1,label:'归档产品'},global:{plugins:[ElementPlus]}});await flushPromises();await w.setProps({modelValue:'active'});resolve('discontinued');await flushPromises();expect(w.text()).toContain('生产：在用');expect(w.text()).toContain('维护')})
  it('shows a badge but no maintenance action to a read-only actor',async()=>{api.getProductionMetadataOptions.mockResolvedValue({canMaintain:false});w=mount(ProductProductionControl,{props:{productId:1,modelValue:'discontinued'},global:{plugins:[ElementPlus]}});await flushPromises();expect(w.text()).toContain('已停产');expect(w.find('button').exists()).toBe(false)})
  it('orders four-digit strings numerically while preserving zeros and putting unassigned last',()=>{const rows=[{codeValue:'0001'},{codeValue:null},{codeValue:'1295'},{codeValue:'1297'},{codeValue:'1296'}];expect(rows.sort(comparePhoneCodes).map(r=>r.codeValue)).toEqual(['1297','1296','1295','0001',null]);expect(productionStatusName()).toBe('待确认');expect(productionStatusName('active')).toBe('在用')})
})


const salesRow = (id=1, fields:Partial<SalesProduct>={}):SalesProduct => ({id,productName:`产品 ${id}`,productType:'product_line',productionStatus:'active',salesStatus:'on_sale',applicationAliases:[`款式 ${id}`],...fields})
async function catalog(open=true) {
  w=mount(ProductSalesCatalog,{props:{modelValue:open},global:{plugins:[ElementPlus],stubs:{Teleport:true,ElSelect:{name:'ElSelect',props:['modelValue','disabled'],emits:['update:modelValue'],template:'<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\',$event.target.value)"><slot/></select>'},ElOption:{props:['value','label'],template:'<option :value="value">{{label}}</option>'}}}})
  await flushPromises()
  return w
}
async function refreshCatalog() {await w!.findAll('button').find(b=>b.text()==='刷新目录')!.trigger('click');await flushPromises()}
async function saveCatalogRow(index=0) {await w!.findAll('article')[index]!.get('button').trigger('click');await flushPromises()}
describe('shared sales catalog refresh',()=>{
  it('loads an already-open catalog and reloads after reopening so newly launched products appear',async()=>{
    api.getSalesCatalog.mockResolvedValue([salesRow()]);await catalog();expect(w!.findAll('article')).toHaveLength(1)
    await w!.setProps({modelValue:false});api.getSalesCatalog.mockResolvedValue([salesRow(),salesRow(2)])
    await w!.setProps({modelValue:true});await flushPromises();expect(w!.findAll('article').map(a=>a.get('strong').text())).toEqual(['产品 1','产品 2'])
    expect(api.getSalesCatalog).toHaveBeenCalledTimes(2)
  })
  it('refreshes clean rows and newly launched products while retaining edits and original conflict values',async()=>{
    api.getSalesCatalog.mockResolvedValue([salesRow(),salesRow(2)]);await catalog()
    w!.findAll('article')[0]!.getComponent({name:'ElSelect'}).vm.$emit('update:modelValue','off_sale')
    await w!.findAll('article')[0]!.get('textarea').setValue('我的未保存别名')
    api.getSalesCatalog.mockResolvedValue([salesRow(1,{salesStatus:'unconfirmed',applicationAliases:['别人修改的别名']}),salesRow(2,{salesStatus:'off_sale'}),salesRow(3)])
    useCollaborationWorkspace().changed();await flushPromises()
    expect(w!.findAll('article')).toHaveLength(3);expect(w!.findAll('article')[0]!.get('textarea').element).toHaveProperty('value','我的未保存别名')
    expect(w!.findAll('article')[1]!.getComponent({name:'ElSelect'}).props('modelValue')).toBe('off_sale');expect(w!.get('[role="status"]').text()).toContain('已保留 1 项未保存修改')
    api.saveSalesProduct.mockRejectedValue(new Error('原值冲突，请核对当前在售状态'))
    await saveCatalogRow()
    expect(api.saveSalesProduct).toHaveBeenCalledWith(expect.objectContaining({id:1,salesStatus:'on_sale',applicationAliases:['款式 1']}),{salesStatus:'off_sale',applicationAliases:['我的未保存别名']},expect.any(String))
    expect(w!.text()).toContain('原值冲突');expect(w!.findAll('article')[0]!.get('textarea').element).toHaveProperty('value','我的未保存别名')
  })
  it('preserves edits typed while a refresh is in flight instead of replacing them on response',async()=>{
    api.getSalesCatalog.mockResolvedValue([salesRow()]);await catalog()
    let finish!:(rows:SalesProduct[])=>void;api.getSalesCatalog.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
    await refreshCatalog();await w!.get('article textarea').setValue('读取期间继续输入')
    finish([salesRow(1,{applicationAliases:['远端更新']}),salesRow(2)]);await flushPromises()
    expect(w!.get('article textarea').element).toHaveProperty('value','读取期间继续输入');expect(w!.findAll('article')).toHaveLength(2)
  })
  it('ignores a stale result from a previous opening and exposes a manual refresh retry',async()=>{
    let finish!:(rows:SalesProduct[])=>void;api.getSalesCatalog.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}))
    await catalog();await w!.setProps({modelValue:false});api.getSalesCatalog.mockResolvedValue([salesRow(2)])
    await w!.setProps({modelValue:true});await flushPromises();finish([salesRow()]);await flushPromises()
    expect(w!.findAll('article').map(a=>a.get('strong').text())).toEqual(['产品 2'])
    api.getSalesCatalog.mockRejectedValueOnce(new Error('目录读取失败'));await refreshCatalog();expect(w!.text()).toContain('目录读取失败');expect(w!.get('article strong').text()).toBe('产品 2')
    api.getSalesCatalog.mockResolvedValue([salesRow(3)]);await refreshCatalog();expect(w!.text()).not.toContain('目录读取失败');expect(w!.get('article strong').text()).toBe('产品 3')
  })
  it('waits for an active save before applying revision refreshes and retains another row draft',async()=>{
    api.getSalesCatalog.mockResolvedValue([salesRow(),salesRow(2)]);await catalog()
    await w!.findAll('article')[0]!.get('textarea').setValue('保存的别名');await w!.findAll('article')[1]!.get('textarea').setValue('另一项草稿')
    let finish!:(row:SalesProduct)=>void;api.saveSalesProduct.mockImplementation(()=>new Promise(resolve=>{finish=resolve}))
    await saveCatalogRow();expect(w!.findAll('article')[0]!.get('textarea').attributes('disabled')).toBeDefined()
    useCollaborationWorkspace().changed();await flushPromises();expect(api.getSalesCatalog).toHaveBeenCalledTimes(1)
    api.getSalesCatalog.mockResolvedValue([salesRow(1,{applicationAliases:['保存的别名']}),salesRow(2),salesRow(3)])
    finish(salesRow(1,{applicationAliases:['保存的别名']}));await flushPromises()
    expect(api.getSalesCatalog).toHaveBeenCalledTimes(2);expect(w!.findAll('article')).toHaveLength(3)
    expect(w!.findAll('article')[1]!.get('textarea').element).toHaveProperty('value','另一项草稿')
    expect(api.saveSalesProduct).toHaveBeenCalledTimes(1)
  })
  it('clears private rows and ignores pending responses or errors when the account changes',async()=>{
    api.getSalesCatalog.mockResolvedValue([salesRow()]);await catalog()
    let fail!:(error:Error)=>void;api.getSalesCatalog.mockImplementationOnce(()=>new Promise((_,reject)=>{fail=reject}))
    await refreshCatalog();useUserStore().token='another-account';await flushPromises();fail(new Error('旧账号私密信息'));await flushPromises()
    expect(w!.findAll('article')).toHaveLength(0);expect(w!.text()).not.toContain('旧账号私密信息');expect(w!.emitted('update:modelValue')?.at(-1)).toEqual([false])
  })
})
