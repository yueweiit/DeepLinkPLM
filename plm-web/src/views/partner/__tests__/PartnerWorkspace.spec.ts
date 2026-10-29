import {DOMWrapper,mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {createMemoryHistory,createRouter} from 'vue-router'
import ElementPlus,{ElSelect,ElOption,ElCheckbox,ElPagination,ElMessageBox,ElFormItem} from 'element-plus'
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'
const api=vi.hoisted(()=>({selectionRow:vi.fn(),selectionDetail:vi.fn(),partnerPage:vi.fn(),partnerDetail:vi.fn(),partnerSave:vi.fn(),deletePartnerProduct:vi.fn(),deletePartnerSpec:vi.fn(),partnerOptions:vi.fn(),newRequestId:vi.fn(()=>"request"),getPhoneSelectionBrands:vi.fn().mockResolvedValue([{id:1,brandId:1,name:"Apple",status:"enabled"}]),getPhoneModels:vi.fn(),getPhoneModelOptions:vi.fn()}))
vi.mock('@/api/modules/partner',()=>api)
vi.mock('@/api/modules/partnerSelections',()=>({selectionRow:api.selectionRow,selectionDetail:api.selectionDetail}))
vi.mock('../selection/SelectionReceiptDialog.vue',()=>({default:{name:'SelectionReceiptDialog',props:['id','version','rows','from'],emits:['close','saved','busy'],template:`<div class="shared-catalog-receipt">共享实际收样<button @click="$emit('close')">取消目录收样</button></div>`}}))
vi.mock('@/api/modules/phoneReference',()=>({getPhoneSelectionBrands:api.getPhoneSelectionBrands,getPhoneModels:api.getPhoneModels,getPhoneModelOptions:api.getPhoneModelOptions}))
vi.mock('../PartnerFiles.vue',()=>({default:{template:'<div/>'}}))
vi.mock('../PartnerPhoto.vue',()=>({default:{template:'<div/>'}}))
vi.mock('../PartnerImportDialog.vue',()=>({default:{template:'<div/>'}}))
vi.mock('@/views/mold/components/PhoneReferenceManager.vue',()=>({default:{template:'<div/>'}}))
import PartnerWorkspace from '../PartnerWorkspace.vue'
import SharedCompatiblePhoneSelector from '@/views/mold/components/SharedCompatiblePhoneSelector.vue'
import OperationSpecPicker from '../operations/OperationSpecPicker.vue'
vi.mock('@/api/modules/partnerOperations',()=>({operationSpecOptions:vi.fn().mockResolvedValue({content:[],totalElements:0,brands:[],productLines:[]}),operationSpecPhones:vi.fn().mockResolvedValue({content:[],totalElements:0,unlinkedCount:0,brands:[],productLines:[]})}))
import {useUserStore} from '@/stores/user'
const phone=(id:number)=>({phoneModelId:id,brandId:1,brandName:'Apple',modelName:'iPhone '+id,phoneModelCode:null,status:'enabled',brandStatus:'enabled',codeLinkStatus:'unassigned'})
const product=()=>({id:7,name:'透明保护壳',code:'PP-7',version:2,status:'draft',specs:[{id:8,name:'Apple iPhone 501 · 透明',specification:'透明',phoneModelIds:[501],phones:[phone(501)],sources:[{supplierCode:'TIM',currency:'CNY'}]}],events:[],files:[],refs:[]})
let wrapper:VueWrapper
beforeEach(()=>{vi.restoreAllMocks();vi.clearAllMocks();localStorage.clear();setActivePinia(createPinia());useUserStore().token='test';useUserStore().permissions=['*'];api.partnerOptions.mockResolvedValue({specs:[],suppliers:[{code:'TIM',name:'Tim'}],customers:[],canReadMoney:false,canWriteMoney:false});api.partnerPage.mockResolvedValue({content:[],totalElements:0});api.partnerDetail.mockResolvedValue(product());api.getPhoneModels.mockResolvedValue({content:[phone(1),phone(2)]});api.getPhoneModelOptions.mockImplementation(async(ids:number[])=>ids.map(phone));api.partnerSave.mockResolvedValue(product());api.selectionDetail.mockResolvedValue({id:9,version:4})})
afterEach(()=>{wrapper?.unmount();document.body.innerHTML=''})
async function start(path='/partner-products'){const router=createRouter({history:createMemoryHistory(),routes:['partner-products','partner-sales','samples'].map(kind=>({path:'/'+kind,component:PartnerWorkspace,meta:{partnerKind:kind}}))});await router.push(path);wrapper=mount({template:"<router-view/>"},{attachTo:document.body,global:{plugins:[router,ElementPlus],stubs:{ElDialog:{props:['modelValue'],template:'<section v-if="modelValue" class="test-dialog"><slot/><slot name="footer"/></section>'}}}});await flushPromises();return router}
async function click(text:string){await wrapper.findAll('button').find(b=>b.text()===text)!.trigger('click');await flushPromises()}
describe('partner product system phone selection',()=>{
 it('shows the persisted readable receipt label and opens the same SP identity without rewriting old labels or locations',async()=>{const actual={id:88,name:'真实目录收样',code:'SP-000088',displayLabel:'OLD-SP-000088',status:'active',sourceType:'catalog',specName:'iPhone 18 · 黑色',ownerName:'国内工程',balances:{onhand:2,available:2,borrowed:0,positions:[{name:'公司样品柜'}]}};const before=JSON.stringify(actual);api.partnerPage.mockResolvedValue({content:[actual],totalElements:1});api.partnerDetail.mockResolvedValue({...actual,version:1,files:[],refs:[],events:[],sourceSnapshot:{},balances:{...actual.balances,retained:0,damaged:0,loans:[],retainedIds:{}}});const router=await start('/samples');expect(wrapper.get('.partner-row').text()).toContain('OLD-SP-000088');expect(wrapper.get('.partner-row').text()).toContain('公司样品柜');expect(wrapper.get('.partner-row').text()).toContain('国内工程');await click('查看／办理');expect(router.currentRoute.value.query.id).toBe('88');expect(api.partnerDetail).toHaveBeenCalledWith('samples',88);expect(api.partnerSave).not.toHaveBeenCalled();expect(JSON.stringify(actual)).toBe(before)})
 it('queries the exact catalog sample source from its visible filter while preserving the distinct legacy product and research sources',async()=>{api.partnerPage.mockImplementation(async(_kind,params)=>({content:params.sourceType==='catalog'?[{id:88,name:'目录选样实收',code:'SP-000088',status:'active',sourceType:'catalog',specName:'iPhone 18 Pro Max · 原黑',ownerName:'国内工程',balances:{onhand:2,available:2,borrowed:0}}]:[],totalElements:params.sourceType==='catalog'?1:0}));await start('/samples');const filter=wrapper.findAllComponents(ElSelect).find(select=>select.props('placeholder')==='全部来源')!;expect(filter.findAllComponents(ElOption).map(option=>({label:option.props('label'),value:option.props('value')}))).toEqual([{label:'产品型号样品',value:'model'},{label:'历史手机真机／模型机',value:'device'},{label:'历史模具试模产出',value:'trial'},{label:'研发／历史项目样品',value:'self'},{label:'合作产品样品',value:'partner'},{label:'目录选样样品',value:'catalog'}]);await filter.trigger('click');await flushPromises();const option=new DOMWrapper(document.body).findAll('.el-select-dropdown__item').find(item=>item.text()==='目录选样样品')!;await option.trigger('click');await flushPromises();expect(api.partnerPage).toHaveBeenLastCalledWith('samples',expect.objectContaining({sourceType:'catalog',page:1}));expect(wrapper.findAll('.partner-row')).toHaveLength(1);expect(wrapper.get('.partner-row').text()).toContain('SP-000088');expect(wrapper.get('.partner-row').text()).toContain('目录选样样品');expect(api.partnerSave).not.toHaveBeenCalled();expect(api.selectionRow).not.toHaveBeenCalled()})
 it('routes a legacy catalogue receipt deep link to the same batch receipt dialog with the exact source and no planned receipt',async()=>{const selected={rowId:'stable-choice',rowVersion:2,name:'目录款',catalogStyleId:1,catalogStyleCode:'CP-000001',catalogStyleLabel:'XD-CP-000001',confirmed:true,sampleQty:9,phoneModelIds:[18],selectedPairs:[],phones:[],fileDetails:[]};api.selectionRow.mockResolvedValue(selected);await start('/samples?new=1&sourceType=catalog&selectionId=9&selectionRowId=stable-choice&from=/partner-selections?id=9');expect(api.selectionRow).toHaveBeenCalledWith(9,'stable-choice');expect(api.selectionDetail).toHaveBeenCalledWith(9);expect(wrapper.getComponent({name:'SelectionReceiptDialog'}).props()).toMatchObject({id:9,version:4,rows:[selected],from:'/partner-selections?id=9'});expect(wrapper.find('.test-dialog').exists()).toBe(false);expect(api.partnerSave).not.toHaveBeenCalled()})
 it('closes the legacy catalogue deep link without writing, and retains the real source and historical sample ledgers',async()=>{api.selectionRow.mockResolvedValue({rowId:'stable-choice',rowVersion:2,name:'目录款',catalogStyleId:1,confirmed:true,phoneModelIds:[18],selectedPairs:[]});const router=await start('/samples?new=1&sourceType=catalog&selectionId=9&selectionRowId=stable-choice');await click('取消目录收样');expect(router.currentRoute.value.path).toBe('/samples');expect(router.currentRoute.value.query).not.toHaveProperty('new');expect(wrapper.find('.shared-catalog-receipt').exists()).toBe(false);expect(api.partnerSave).not.toHaveBeenCalled()})
 it('opens system phone choices and saves only confirmed model IDs with optional variant text',async()=>{
  await start();await click('新增产品');await wrapper.get('.test-dialog .el-input input').setValue('磁吸壳')
  const picker=wrapper.getComponent(SharedCompatiblePhoneSelector);await picker.get('.phone-model-trigger').trigger('click');await flushPromises()
  expect(api.getPhoneModels).toHaveBeenCalledWith(expect.objectContaining({keyword:'',selectionSearch:true}))
  const boxes=picker.findAllComponents(ElCheckbox).filter(v=>v.text().includes('iPhone'))
  for(const box of boxes)box.vm.$emit('change',true)
  await flushPromises();expect(picker.props('modelValue')).toEqual([])
  await new DOMWrapper(document.body).findAll('.phone-model-panel button').find(v=>v.text()==='完成选择（2）')!.trigger('click');await flushPromises();await click('保存草稿')
  expect(api.partnerSave).toHaveBeenCalledWith('partner-products',expect.objectContaining({name:'磁吸壳',specs:[expect.objectContaining({phoneModelIds:[1,2],specification:''})]}),undefined)
 })
 it('keeps out-of-page saved models in the selected area and removes one before confirming without retyping',async()=>{
  await start('/partner-products?id=7');await click('编辑资料');const picker=wrapper.getComponent(SharedCompatiblePhoneSelector)
  await picker.get('.phone-model-trigger').trigger('click');await flushPromises();expect(new DOMWrapper(document.body).get('.phone-model-panel .selected-tags').text()).toContain('iPhone 501')
  picker.findAllComponents(ElCheckbox).find(v=>v.text().includes('iPhone 2'))!.vm.$emit('change',true);await flushPromises()
  await new DOMWrapper(document.body).get('.phone-model-panel .selected-tags .el-tag__close').trigger('click');await flushPromises();await new DOMWrapper(document.body).findAll('.phone-model-panel button').find(v=>v.text()==='完成选择（1）')!.trigger('click');await flushPromises();await click('保存草稿')
  expect(api.partnerSave).toHaveBeenCalledWith('partner-products',expect.objectContaining({specs:[expect.objectContaining({id:8,phoneModelIds:[2],specification:'透明'})]}),7)
 })
 it('still saves a name-only draft and preserves old handwritten specifications without inferring a phone',async()=>{await start();await click('新增产品');await wrapper.get('.test-dialog .el-input input').setValue('只有名称');await click('保存草稿');expect(api.partnerSave.mock.calls[0][1].specs).toEqual([]);wrapper.unmount();api.partnerDetail.mockResolvedValue({...product(),specs:[{id:8,name:'历史手写型号／蓝色',sources:[]}]});await start('/partner-products?id=7');await click('编辑资料');expect(wrapper.getComponent(SharedCompatiblePhoneSelector).props('modelValue')).toEqual([]);await click('保存草稿');expect(api.partnerSave.mock.calls[1][1].specs[0]).toMatchObject({phoneModelIds:[],specification:'历史手写型号／蓝色'})})
})

describe('partner compact product directory integration',()=>{
 it('summarizes 164 specifications in one product row, deduplicates suppliers and opens the original detail route',async()=>{
  const row={...product(),listing:'unlisted',specs:Array.from({length:164},(_,n)=>({id:n+1,name:'不应铺在目录的规格 '+n,sources:[{supplierCode:'TIM',supplierName:'Tim'}]}))}
  api.partnerPage.mockResolvedValue({content:[row],totalElements:1,statistics:[{status:'draft',count:1}]})
  const router=await start()
  expect(wrapper.findAll('.product-row')).toHaveLength(1)
  expect(wrapper.text()).toContain('共 1 个产品')
  expect(wrapper.get('.product-spec-count').text()).toContain('164 个规格')
  expect(wrapper.get('.product-suppliers').text()).toBe('供货来源Tim')
  expect(wrapper.text()).not.toContain('不应铺在目录')
  expect(wrapper.get('.product-status').text()).toContain('选品草稿')
  expect(wrapper.get('.product-status').text()).toContain('网站已下架')
  await click('查看详情')
  expect(router.currentRoute.value.query).toMatchObject({id:'7',from:'/partner-products'})
  expect(api.partnerDetail).toHaveBeenCalledWith('partner-products',7)
 })

 it('keeps name-only products and unknown website states visible without invented suppliers',async()=>{
  api.partnerPage.mockResolvedValue({content:[{...product(),specs:[],listing:null}],totalElements:1})
  await start()
  expect(wrapper.get('.product-spec-count').text()).toContain('0 个规格')
  expect(wrapper.get('.product-suppliers').text()).toContain('待补供应商')
  expect(wrapper.get('.product-status').text()).toContain('网站未记录')
 })

 it('retains server-side directory search and pagination, resetting the page when the query changes',async()=>{
  api.partnerPage.mockResolvedValue({content:[product()],totalElements:61})
  await start()
  const pager=wrapper.getComponent(ElPagination)
  pager.vm.$emit('update:current-page',2);pager.vm.$emit('current-change',2);await flushPromises()
  expect(api.partnerPage).toHaveBeenLastCalledWith('partner-products',expect.objectContaining({page:2}))
  await wrapper.get('.partner-search input').setValue('NSF4030120404');await click('查询')
  expect(api.partnerPage).toHaveBeenLastCalledWith('partner-products',expect.objectContaining({keyword:'NSF4030120404',page:1}))
  expect(api.partnerSave).not.toHaveBeenCalled()
 })

 it('preserves product information, files and history without crowding the default specification list',async()=>{
  api.partnerDetail.mockResolvedValue({...product(),site:'CRM',externalId:'原商品ID',notes:'原始备注',listing:'unlisted',events:[{id:1,action:'product_save',time:'2026-10-17T01:00:00',operator:'原操作人',data:{reason:'原始维护原因'}}]})
  await start('/partner-products?id=7')
  expect(wrapper.find('.product-specs').exists()).toBe(true)
  expect(wrapper.text()).not.toContain('原始备注')
  await click('产品资料')
  expect(wrapper.text()).toContain('原始备注')
  expect(wrapper.text()).toContain('原商品ID')
  expect(wrapper.text()).toContain('保存采用资料版本')
  expect(wrapper.find('.product-specs').exists()).toBe(false)
  await click('往来记录')
  expect(wrapper.text()).toContain('原始维护原因')
  expect(wrapper.text()).toContain('原操作人')
  await click('规格与供货')
  expect(wrapper.find('.product-specs').exists()).toBe(true)
  expect(api.partnerSave).not.toHaveBeenCalled()
 })

 it('recovers after a detail read failure without introducing a new write path',async()=>{
  api.partnerDetail.mockRejectedValueOnce(new Error('临时读取失败'))
  await start('/partner-products?id=7')
  expect(wrapper.text()).toContain('临时读取失败')
  await click('重新加载')
  expect(wrapper.find('.product-specs').exists()).toBe(true)
  expect(api.partnerSave).not.toHaveBeenCalled()
 })

 it.each(['partner-sales','samples'])('keeps the existing %s ledger fields and actions',async kind=>{
  api.partnerPage.mockResolvedValue({content:[{...product(),customerSnapshot:{name:'真实客户'},summary:'订单商品',stats:{shipped:4,ordered:8},specName:'样品规格',sourceType:'partner',ownerName:'工程',balances:{onhand:8,available:5,borrowed:3,positions:[{name:'A柜'}]}}],totalElements:1})
  await start('/'+kind)
  expect(api.partnerPage).toHaveBeenCalledWith(kind,expect.anything())
  expect(wrapper.findAll('.partner-row')).toHaveLength(1)
  expect(wrapper.text()).toContain(kind==='partner-sales'?'真实客户':'样品规格')
  expect(wrapper.text()).toContain(kind==='partner-sales'?'正常发货 4 / 8 件':'在库 8 · 可借 5 · 借出 3')
  expect(wrapper.text()).toContain('查看／办理')
  expect(wrapper.find('.product-catalog').exists()).toBe(false)
 })
})
describe('commercial selection preserves original form semantics',()=>{
 it('carries an out-of-preload spec and its supplier into the same sales draft',async()=>{
  api.partnerSave.mockRejectedValueOnce(Error('模拟保存失败，保留本次草稿'));
  api.partnerOptions.mockResolvedValue({specs:[],suppliers:[{code:'TIM',name:'Tim'}],customers:[],canReadMoney:true,canWriteMoney:true});await start('/partner-sales');await click('登记询价');
  const picker=wrapper.getComponent(OperationSpecPicker),spec={id:5001,code:'PS-5001',name:'0185 透明',productName:'已有保护壳',details:{phoneModelIds:[101],sources:[{supplierCode:'TIM',supplierName:'Tim',currency:'CNY',priceCents:850}]}};
  picker.vm.$emit('update:modelValue',5001);picker.vm.$emit('selected',spec);await flushPromises();await click('保存草稿');
  expect(api.partnerSave).toHaveBeenCalledWith('partner-sales',expect.objectContaining({lines:[expect.objectContaining({specId:5001,supplierCode:'TIM',unitPrice:'8.5',qty:1})]}),undefined)
 })
})

describe('partner product and specification deletion',()=>{
 it('shows directory deletion for a product without suppliers and cancellation leaves it intact',async()=>{
  api.partnerPage.mockResolvedValue({content:[{...product(),specs:[]}],totalElements:1})
  const confirm=vi.spyOn(ElMessageBox,'confirm').mockRejectedValue('cancel')
  await start();await click('删除产品')
  expect(confirm).toHaveBeenCalledWith(expect.stringContaining('0 个规格'),expect.any(String),expect.objectContaining({confirmButtonText:'确认删除',cancelButtonText:'取消'}))
  expect(api.deletePartnerProduct).not.toHaveBeenCalled()
  expect(wrapper.findAll('.product-row')).toHaveLength(1)
 })

 it('confirms the product and all 164 specs, removes the last row on page two and refreshes page one',async()=>{
  const row={...product(),specs:Array.from({length:164},(_,id)=>({id,name:'型号 '+id,sources:[]}))}
  api.partnerPage.mockResolvedValue({content:[row],totalElements:31})
  api.deletePartnerProduct.mockResolvedValue({id:7,deleted:true})
  const confirm=vi.spyOn(ElMessageBox,'confirm').mockResolvedValue(Object.assign('confirm' as const,{value:'',action:'confirm' as const}))
  await start()
  const pager=wrapper.getComponent(ElPagination);pager.vm.$emit('update:current-page',2);pager.vm.$emit('current-change',2);await flushPromises()
  api.partnerPage.mockResolvedValue({content:[],totalElements:30})
  await click('删除产品')
  expect(confirm.mock.calls[0][0]).toContain('透明保护壳')
  expect(confirm.mock.calls[0][0]).toContain('164 个规格')
  expect(api.deletePartnerProduct).toHaveBeenCalledWith(7,{version:2,requestId:'request'})
  expect(api.partnerPage).toHaveBeenLastCalledWith('partner-products',expect.objectContaining({page:1}))
  expect(wrapper.findAll('.product-row')).toHaveLength(0)
 })

 it('deletes a saved spec by its real ID and immediately updates the existing detail and history',async()=>{
  const updated={...product(),version:3,specs:[],events:[{id:3,action:'spec_delete',operator:'工程师',time:'2026-10-19',data:{specId:8,name:'原规格'}}]}
  api.deletePartnerSpec.mockResolvedValue(updated)
  vi.spyOn(ElMessageBox,'confirm').mockResolvedValue(Object.assign('confirm' as const,{value:'',action:'confirm' as const}))
  await start('/partner-products?id=7');await click('删除规格')
  expect(api.deletePartnerSpec).toHaveBeenCalledWith(7,8,{version:2,requestId:'request'})
  expect(wrapper.get('.product-specs').text()).toContain('共 0 个规格')
  expect(wrapper.text()).toContain('维护版本 3')
  await click('往来记录');expect(wrapper.text()).toContain('删除规格')
  expect(api.partnerSave).not.toHaveBeenCalled()
 })

 it('returns from a deleted product to the catalog rather than leaving a broken detail route',async()=>{
  api.deletePartnerProduct.mockResolvedValue({id:7,deleted:true})
  vi.spyOn(ElMessageBox,'confirm').mockResolvedValue(Object.assign('confirm' as const,{value:'',action:'confirm' as const}))
  const router=await start('/partner-products?id=7');await click('删除产品')
  expect(router.currentRoute.value.path).toBe('/partner-products')
  expect(router.currentRoute.value.query.id).toBeUndefined()
  expect(wrapper.find('.product-catalog').exists()).toBe(true)
 })

 it('keeps the product on a rejected delete, displays the server reason and allows a fresh retry',async()=>{
  api.deletePartnerSpec.mockRejectedValueOnce(new Error('已有样品关联，不能删除规格')).mockResolvedValue({...product(),version:3,specs:[]})
  vi.spyOn(ElMessageBox,'confirm').mockResolvedValue(Object.assign('confirm' as const,{value:'',action:'confirm' as const}))
  await start('/partner-products?id=7');await click('删除规格')
  expect(wrapper.text()).toContain('已有样品关联，不能删除规格')
  expect(wrapper.findAll('.spec-row')).toHaveLength(1)
  await click('刷新后重试');await click('删除规格')
  expect(api.deletePartnerSpec).toHaveBeenCalledTimes(2)
  expect(wrapper.findAll('.spec-row')).toHaveLength(0)
 })

 it('hides every deletion entry from a read-only user',async()=>{
  useUserStore().permissions=['partner:view'];api.partnerPage.mockResolvedValue({content:[product()],totalElements:1})
  const router=await start()
  expect(wrapper.text()).not.toContain('删除产品')
  await router.push('/partner-products?id=7');await flushPromises()
  expect(wrapper.text()).not.toContain('删除产品')
  expect(wrapper.text()).not.toContain('删除规格')
 })

 it('does not submit twice while a delete request is pending',async()=>{
  let complete!: (value:unknown)=>void
  api.deletePartnerSpec.mockImplementationOnce(()=>new Promise(resolve=>{complete=resolve}))
  vi.spyOn(ElMessageBox,'confirm').mockResolvedValue(Object.assign('confirm' as const,{value:'',action:'confirm' as const}))
  await start('/partner-products?id=7');await click('删除规格')
  expect(wrapper.findAll('button').find(button=>button.text()==='删除规格')!.attributes('disabled')).toBeDefined()
  wrapper.getComponent({name:'PartnerProductSpecs'}).vm.$emit('delete',product().specs[0]);await flushPromises()
  expect(api.deletePartnerSpec).toHaveBeenCalledTimes(1)
  complete({...product(),version:3,specs:[]});await flushPromises()
 })

 it('can remove an unsaved spec without deleting an existing record',async()=>{
  await start();await click('新增产品');await click('移除未保存规格')
  expect(wrapper.findAll('.form-spec')).toHaveLength(0)
  expect(api.deletePartnerSpec).not.toHaveBeenCalled()
 })

 it('cancels the pending deletion when the login identity changes during confirmation',async()=>{
  let confirm!:()=>void
  vi.spyOn(ElMessageBox,'confirm').mockImplementation(()=>new Promise(resolve=>{confirm=()=>resolve(Object.assign('confirm' as const,{value:'',action:'confirm' as const}))}))
  await start('/partner-products?id=7');await click('删除产品')
  useUserStore().token='another-session';confirm();await flushPromises()
  expect(api.deletePartnerProduct).not.toHaveBeenCalled()
  expect(wrapper.findAll('.spec-row')).toHaveLength(1)
 })
})
