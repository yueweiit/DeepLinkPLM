import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElDialog, ElInputNumber, ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CatalogStyle } from '@/types/partnerCatalog'
import Dialog from '../catalog/CatalogSelectionDialog.vue'
import SampleColorSelect from '../selection/SampleColorSelect.vue'
const api=vi.hoisted(()=>({add:vi.fn(),preview:vi.fn()}))
vi.mock('@/api/modules/partnerCatalog',()=>({addCatalogSelection:api.add,previewCatalogSelection:api.preview}))
vi.mock('@/api/modules/partner',()=>({newRequestId:()=>crypto.randomUUID()}))
vi.mock('../PartnerPhoto.vue',()=>({default:{props:['id'],template:'<span>本款图片</span>'}}))
const identity={sourceKind:'quote' as const,recordId:72,rowId:'stable-source',rowVersion:5,recordVersion:8}
const source=(overrides:Partial<CatalogStyle>&{sampleColors?:string[]}={}):CatalogStyle=>({...identity,sourceCode:'SQ-72',sourceName:'供应商全量资料',supplierCode:'SUP-REAL',supplierName:'真实供方',name:'真实磁吸款',phoneScopeMode:'supplier',phoneModelIds:[17,18],phones:[17,18].map(phoneModelId=>({phoneModelId,id:phoneModelId,aliases:[],codeItemId:phoneModelId,codeLinkStatus:'linked',status:'enabled',brandId:1,brandName:'Apple',modelName:`iPhone ${phoneModelId}`,phoneModelCode:String(phoneModelId).padStart(4,'0')})),refs:[],fileDetails:[],...overrides})
const result={selection:{id:21,code:'SL-21',name:'真实选品目录',version:9},rowIds:['new-row'],addedCount:1,reusedCount:0,replayed:false}
let wrapper:VueWrapper
beforeEach(()=>{vi.clearAllMocks();api.add.mockResolvedValue(result);api.preview.mockImplementation(async data=>({canAdd:true,previewToken:'signed-preview',items:data.items.map((item:unknown)=>({...item as object,name:'真实磁吸款',addedPhoneModelIds:[],reusedPhoneModelIds:[],messages:[]})),addedCount:1,reusedCount:0,newPhoneCount:0,messages:[]}))})
afterEach(()=>{wrapper?.unmount();document.body.innerHTML='';vi.restoreAllMocks()})
async function start(items=[source()],realDialog=false){wrapper=mount(Dialog,{props:{modelValue:false,supplierCode:'SUP-REAL',supplierName:'真实供方',items,canWrite:true},attachTo:document.body,global:{plugins:[ElementPlus],stubs:realDialog?{}:{ElDialog:{props:['modelValue','beforeClose','closeOnClickModal'],template:'<section v-if="modelValue"><slot/><slot name="footer"/></section>'}}}});await wrapper.setProps({modelValue:true});await flushPromises()}
const button=(text:string)=>{const value=wrapper.findAll('button').find(item=>item.text()===text);expect(value,`button ${text}`).toBeDefined();return value!}
async function click(text='加入选品目录'){await button(text).trigger('click');await flushPromises()}
const rowColor=(index=0)=>wrapper.findAll('.selection-model-row')[index]!.getComponent(SampleColorSelect)

describe('add complete supplier styles to the selection directory',()=>{
 it('joins checked styles without asking for phones, quantities or a destination',async()=>{
  await start([source(),source({rowId:'second',name:'仅Air款',phoneModelIds:[19],phones:[]})])
  expect(wrapper.findComponent(ElInputNumber).exists()).toBe(false);expect(wrapper.text()).not.toContain('统一选择手机型号');expect(wrapper.text()).not.toContain('拟拿样')
  expect(button('加入选品目录').attributes('disabled')).toBeUndefined();expect(api.preview).not.toHaveBeenCalled();expect(api.add).not.toHaveBeenCalled()
  await click();expect(api.add.mock.calls[0][0]).toMatchObject({supplierCode:'SUP-REAL',targetMode:'supplier_directory',items:[{...identity,phoneModelIds:[],selectionIntent:'style'},{...identity,rowId:'second',phoneModelIds:[],selectionIntent:'style'}]})
  expect(api.add.mock.calls[0][0]).not.toHaveProperty('targetSelectionId');expect(wrapper.emitted('added')).toHaveLength(1)
 })
 it.each(['unresolved','unspecified',undefined] as const)('allows a style with incomplete supplier phones (%s) without guessing a range',async mode=>{
  const item=source({phoneScopeMode:mode,phoneModelIds:[],phones:[],modelText:'原件型号待核对'});await start([item]);expect(wrapper.text()).toContain('供方型号待补');expect(wrapper.text()).toContain('原件型号待核对');await click()
  expect(api.add.mock.calls[0][0].items).toEqual([{...identity,phoneModelIds:[],selectionIntent:'style'}]);expect(item.phoneModelIds).toEqual([])
 })
 it('shows current supply as a readonly summary and never submits client phone authority',async()=>{
  await start([source({unavailablePhoneModelIds:[17]})]);expect(wrapper.text()).toContain('全部可供型号');expect(wrapper.text()).toContain('随供方目录更新');expect(wrapper.find('.selection-scope').text()).toContain('1')
  await click();expect(api.add.mock.calls[0][0].items[0].phoneModelIds).toEqual([]);expect(api.add.mock.calls[0][0].items[0]).not.toHaveProperty('sampleQty')
 })
 it('displays the original engineering colours by default without materializing a missing key',async()=>{
  await start([source({color:'橙色、白色'})]);expect(rowColor().props('modelValue')).toEqual(['橙色','白色']);await click();expect(api.add.mock.calls[0][0].items[0]).not.toHaveProperty('sampleColors')
 })
 it('labels partially matched supply without claiming all source models are known',async()=>{
  await start([source({phoneMatchIssues:['原表另有未关联型号']})]);expect(wrapper.get('.selection-scope').text()).toContain('已关联可供型号');expect(wrapper.get('.selection-scope').text()).toContain('部分供方型号待核对');expect(wrapper.get('.selection-scope').text()).not.toContain('全部可供型号');await click();expect(api.add.mock.calls[0][0].items[0].phoneModelIds).toEqual([])
 })
 it('preserves an explicitly empty source colour choice',async()=>{
  await start([source({color:'橙色',sampleColors:[]})]);expect(rowColor().props('modelValue')).toEqual([]);await click();expect(api.add.mock.calls[0][0].items[0]).not.toHaveProperty('sampleColors')
 })
 it('sends a deliberate colour clear as [] and leaves the source untouched',async()=>{
  const item=source({color:'橙色'});await start([item]);rowColor().vm.$emit('update:modelValue',[]);await flushPromises();await click();expect(api.add.mock.calls[0][0].items[0].sampleColors).toEqual([]);expect(item.color).toBe('橙色')
 })
 it('applies optional batch colours while preserving a manually edited style',async()=>{
  await start([source(),source({rowId:'second'})]);rowColor().vm.$emit('update:modelValue',['白色']);wrapper.findAllComponents(SampleColorSelect)[0]!.vm.$emit('update:modelValue',['黑色']);await flushPromises();await click('应用颜色');await click()
  expect(api.add.mock.calls[0][0].items.map((item:{sampleColors:string[]})=>item.sampleColors)).toEqual([['白色'],['黑色']])
 })
 it('can exclude and restore a checked style without erasing its entered colours',async()=>{
  await start();rowColor().vm.$emit('update:modelValue',['紫色']);await flushPromises();await click('本次不加入');expect(button('加入选品目录').attributes('disabled')).toBeDefined();await click('恢复本款');expect(rowColor().props('modelValue')).toEqual(['紫色']);await click();expect(api.add.mock.calls[0][0].items[0].sampleColors).toEqual(['紫色'])
 })
 it('keeps entered colours when discard is cancelled and resets on the next opening',async()=>{
  const confirm=vi.spyOn(ElMessageBox,'confirm').mockRejectedValueOnce('cancel').mockResolvedValueOnce('confirm' as never);await start();rowColor().vm.$emit('update:modelValue',['紫色']);await flushPromises();await click('取消');expect(wrapper.emitted('update:modelValue')).toBeUndefined();expect(rowColor().props('modelValue')).toEqual(['紫色']);await click('取消');expect(confirm).toHaveBeenCalledTimes(2)
  await wrapper.setProps({modelValue:false});await wrapper.setProps({modelValue:true});await flushPromises();expect(rowColor().props('modelValue')).toEqual([])
 })
 it('closes an untouched style confirmation without asking to discard invented sample details',async()=>{
  const confirm=vi.spyOn(ElMessageBox,'confirm');await start([source({color:'白色'})]);await click('取消');expect(confirm).not.toHaveBeenCalled();expect(wrapper.emitted('update:modelValue')).toEqual([[false]]);expect(api.add).not.toHaveBeenCalled()
 })
 it('keeps the real close icon behind the same unsaved-colour confirmation',async()=>{
  vi.spyOn(ElMessageBox,'confirm').mockRejectedValue('cancel');await start([source()],true);wrapper.findComponent(SampleColorSelect).vm.$emit('update:modelValue',['黑色']);await flushPromises();expect(wrapper.getComponent(ElDialog).props('closeOnClickModal')).toBe(false);document.querySelector<HTMLButtonElement>('.catalog-selection-dialog-shell .el-dialog__headerbtn')!.click();await flushPromises();expect(wrapper.emitted('update:modelValue')).toBeUndefined()
 })
 it('shows source validation failures inline and does not perform the add',async()=>{
  api.preview.mockResolvedValue({canAdd:false,previewToken:'',items:[{name:'原款',messages:['来源版本已改变']}],messages:[]});await start();await click();expect(wrapper.get('.selection-errors').text()).toContain('原款：来源版本已改变');expect(api.add).not.toHaveBeenCalled()
 })
 it('passes exact existing-directory context without using the preview response as new authority',async()=>{
  api.preview.mockResolvedValue({canAdd:true,previewToken:'target-token',targetSelectionId:999,targetVersion:99,items:[],messages:[]});await start();await wrapper.setProps({targetSelectionId:77,targetVersion:8});await click();expect(api.add.mock.calls[0][0]).toMatchObject({targetSelectionId:77,targetVersion:8,previewToken:'target-token'})
 })
 it('revalidates known save failures while retaining entered colours and original target',async()=>{
  api.add.mockRejectedValueOnce(new Error('来源或目标已变化')).mockResolvedValue(result);await start();await wrapper.setProps({targetSelectionId:77,targetVersion:8});rowColor().vm.$emit('update:modelValue',['橙色']);await flushPromises();await click();expect(rowColor().props('modelValue')).toEqual(['橙色']);await click();expect(api.preview).toHaveBeenCalledTimes(2);expect(api.add.mock.calls[1][0]).toMatchObject({targetSelectionId:77,items:[expect.objectContaining({sampleColors:['橙色']})]})
 })
 it('replays an unknown save with the exact original body and token, without a second preview',async()=>{
  api.add.mockRejectedValueOnce({code:'ECONNABORTED'}).mockResolvedValue({...result,replayed:true});await start();rowColor().vm.$emit('update:modelValue',[]);await flushPromises();await click();const original=JSON.parse(JSON.stringify(api.add.mock.calls[0][0]));expect(button('取消').attributes('disabled')).toBeDefined();expect(rowColor().props('disabled')).toBe(true);await click('核对保存结果');expect(api.preview).toHaveBeenCalledTimes(1);expect(api.add.mock.calls[1][0]).toEqual(original);expect(wrapper.emitted('added')).toHaveLength(1)
 })
 it('refuses to replay under a different supplier and resumes only under the original context',async()=>{
  api.add.mockRejectedValueOnce({code:'ECONNABORTED'}).mockResolvedValue(result);await start();await wrapper.setProps({targetSelectionId:77,targetVersion:8});await click();await wrapper.setProps({supplierCode:'OTHER',targetSelectionId:88});await click('核对保存结果');expect(wrapper.text()).toContain('待核对的保存属于原供应商');expect(api.add).toHaveBeenCalledTimes(1);await wrapper.setProps({supplierCode:'SUP-REAL',targetSelectionId:77});await click('核对保存结果');expect(api.add).toHaveBeenCalledTimes(2)
 })
 it('drops an in-flight preview after the source or destination context changes',async()=>{
  let resolve!:(value:unknown)=>void;api.preview.mockImplementationOnce(()=>new Promise(done=>{resolve=done}));await start();await click();await wrapper.setProps({targetSelectionId:88,targetVersion:9});resolve({canAdd:true,previewToken:'stale',items:[],messages:[]});await flushPromises();expect(api.add).not.toHaveBeenCalled()
 })
 it('blocks double submission and direct handlers after write permission is withdrawn',async()=>{
  let resolve!:(value:unknown)=>void;api.add.mockImplementationOnce(()=>new Promise(done=>{resolve=done}));await start();await click();await click();expect(api.add).toHaveBeenCalledTimes(1);resolve(result);await flushPromises();await wrapper.setProps({canWrite:false});await (wrapper.vm as unknown as {submit:()=>Promise<void>}).submit();expect(api.add).toHaveBeenCalledTimes(1)
 })
})
