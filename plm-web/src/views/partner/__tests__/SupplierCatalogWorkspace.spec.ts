import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus, { ElCheckbox, ElInputNumber, ElPagination, ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CatalogStyle } from '@/types/partnerCatalog'

const api = vi.hoisted(() => ({ catalogSuppliers: vi.fn(), catalogStyles: vi.fn(), catalogStyle: vi.fn(),renameCatalogStyle:vi.fn(),addCatalogSelection: vi.fn(), previewCatalogSelection:vi.fn(), selectionPage: vi.fn() }))
vi.mock('@/api/modules/partnerCatalog', () => api)
vi.mock('@/api/modules/partnerSelections', () => ({ selectionPage: api.selectionPage }))
vi.mock('@/api/modules/partner', () => ({ newRequestId: () => crypto.randomUUID() }))
vi.mock('../PartnerPhoto.vue', () => ({ default: { props: ['id'], template: '<span class="test-photo" :data-photo="id">图片</span>' } }))
vi.mock('../PartnerFiles.vue', () => ({ default: { props: ['files'], template: '<section class="test-files"><span v-for="file in files" :key="file.id">{{file.name}}</span></section>' } }))
vi.mock('../PartnerWorkspace.vue', () => ({ default: { template: '<div class="existing-workspace">现有产品办理</div>' } }))
vi.mock('../catalog/CatalogModelSheetDialog.vue',()=>({default:{name:'CatalogModelSheetDialog',props:['row'],emits:['saved','close'],template:'<section class="model-sheet-owner">单款型号表核对：{{row.name}}</section>'}}))
vi.mock('@/components/PhoneModelMultiSelect/index.vue',()=>({default:{name:'PhoneModelMultiSelect',props:['modelValue','members','allowedIds'],template:'<button class="choose-test-model" @click="$emit(\'update:modelValue\',[allowedIds[0]])">选择本款首个已知型号</button>'}}))
import SupplierCatalogWorkspace from '../catalog/SupplierCatalogWorkspace.vue'
import ProductCatalogEntry from '../ProductCatalogEntry.vue'
import catalogSource from '../catalog/SupplierCatalogWorkspace.vue?raw'
import detailSource from '../catalog/SupplierStyleDetail.vue?raw'

const supplier = { supplierCode: 'TEST-SUP', supplierName: '测试供应商', shortCode: 'TS', styleCount: 62 }
const phones = [{ phoneModelId: 201, id: 201, brandId: 1, brandName: 'Apple', modelName: 'iPhone 11', phoneModelCode: '0185', aliases: [], codeItemId: 1, codeLinkStatus: 'linked', status: 'enabled' }]
const row = (overrides: Partial<CatalogStyle> = {}): CatalogStyle => ({ sourceKind: 'selection', recordId: 17, rowId: 'stable-row', rowVersion: 3, sourceCode: 'SL-17', sourceName: '实际来源资料', recordVersion: 4, supplierCode: supplier.supplierCode, supplierName: supplier.supplierName, name: '磁吸支架款', catalogStyleId:41,catalogStyleCode:'CP-000041',catalogStyleLabel:'TS-CP-000041',moqQuantity:20,moqUnit:'个',moqScope:'model_color',priceUnit:'件',supplierItemCode: 'ORIGINAL-7', modelText: 'IP11', phoneModelIds: [201], phones, color: '供方蓝色', material: 'TPU+PC', moq: '每色每型号20个', unitPrice: '12.50', currency: 'CNY', refs: [{ id: 501, type: 'file' }], fileDetails: [{ id: 501, name: '本款原图.png', version: 'V1', type: 'image/png', size: 20 }], canWrite: true, canReadCost: true, ...overrides })
const page = (content: unknown[], extra = {}) => ({ content, totalElements: content.length, page: 1, size: 30, totalPages: 1, canWrite: true, canReadCost: true, ...extra })
let wrapper: VueWrapper
beforeEach(() => {
  vi.clearAllMocks()
  api.catalogSuppliers.mockResolvedValue(page([supplier]))
  api.catalogStyles.mockResolvedValue(page([row()], { supplier, totalElements: 62 }))
  api.catalogStyle.mockResolvedValue(row())
  api.selectionPage.mockResolvedValue(page([{ id: 77, code: 'SL-77', name: '已有真实选品', version: 8, canWrite: true }]))
  api.addCatalogSelection.mockResolvedValue({ selection: { id: 77, code: 'SL-77', name: '已有真实选品', version: 9 }, addedCount: 1, reusedCount: 0, rowIds: ['new-row'], replayed: false })
  api.previewCatalogSelection.mockResolvedValue({canAdd:true,previewToken:'signed-real-pairs',items:[],addedCount:1,reusedCount:0,newPhoneCount:1,messages:[]})
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '';vi.restoreAllMocks() })
async function start(path = '/partner-products', entry = false) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/partner-products', component: entry ? ProductCatalogEntry : SupplierCatalogWorkspace, meta: { partnerKind: 'partner-products' } }, { path: '/partner-selections', component: { template: '<div>原选品工作台</div>' } }, { path: '/suppliers', component: { template: '<div>原供应商档案</div>' } }] })
  await router.push(path)
  wrapper = mount({ template: '<router-view/>' }, { attachTo: document.body, global: { plugins: [router, ElementPlus], stubs: { ElDialog: { props: ['modelValue'], template: '<section v-if="modelValue" class="test-dialog"><slot/><slot name="footer"/></section>' } } } })
  await flushPromises()
  return router
}
async function click(text: string) { const button = wrapper.findAll('button').find(button => button.text() === text); expect(button, text).toBeDefined(); await button!.trigger('click'); await flushPromises() }

async function chooseCatalogStyle(index=0){if(wrapper.find('.detail-scroll').exists())await click('返回款式列表');wrapper.findAll('.catalog-style-check')[index].getComponent(ElCheckbox).vm.$emit('change',true);await flushPromises();await click('选择样品')}
async function chooseFirstRowModel(index = 0) {
  const row = wrapper.findAll('.selection-model-row')[index]!
  if (row.get('.selection-adjust-button').attributes('aria-expanded') !== 'true') { await row.get('.selection-adjust-button').trigger('click'); await flushPromises() }
  await row.get('.choose-test-model').trigger('click'); await flushPromises()
}

describe('supplier-first product catalog', () => {
  it('renames a legacy imported style directly from the list, submits only the name and current versions, and refreshes without moving the page',async()=>{
    const original=row({name:'凡影大孔手机壳'}),current={...original,rowVersion:5,recordVersion:6},corrected={...original,name:'凡影精孔手机壳'}
    api.catalogStyles.mockResolvedValueOnce(page([original],{supplier})).mockResolvedValueOnce(page([corrected],{supplier}));api.catalogStyle.mockResolvedValue(current);api.renameCatalogStyle.mockResolvedValue({name:corrected.name,changed:true});vi.spyOn(ElMessageBox,'prompt').mockImplementation(()=>Promise.resolve({value:corrected.name,action:'confirm'}) as ReturnType<typeof ElMessageBox.prompt>)
    const router=await start('/partner-products?supplierCode=TEST-SUP&catalogPage=2');await wrapper.get('[aria-label="修改凡影大孔手机壳名称"]').trigger('click');await flushPromises()
    expect(api.renameCatalogStyle).toHaveBeenCalledWith('TEST-SUP',current,{name:'凡影精孔手机壳',rowVersion:5,recordVersion:6,requestId:expect.any(String)});expect(wrapper.get('.catalog-style-name').text()).toContain('凡影精孔手机壳');expect(router.currentRoute.value.query.catalogPage).toBe('2');expect(api.addCatalogSelection).not.toHaveBeenCalled();expect(api.catalogStyles).toHaveBeenCalledTimes(2)
    await wrapper.get('.catalog-style-name button').trigger('click');await flushPromises();expect(wrapper.findAll('button').some(button=>button.text()==='修改名称')).toBe(true)
  })
  it('keeps the current detail and does not write or refresh when the rename is cancelled',async()=>{vi.spyOn(ElMessageBox,'prompt').mockRejectedValue('cancel');await start('/partner-products?supplierCode=TEST-SUP&sourceKind=selection&recordId=17&rowId=stable-row');await click('修改名称');expect(api.renameCatalogStyle).not.toHaveBeenCalled();expect(wrapper.get('.detail-heading h2').text()).toBe('磁吸支架款');expect(api.catalogStyles).not.toHaveBeenCalled()})
  it('loads the selected source detail before choosing models and exposes optional recorded colours even without cost permission',async()=>{api.catalogStyles.mockResolvedValue(page([row({pairings:undefined})],{supplier,canReadCost:false}));api.catalogStyle.mockResolvedValue(row({rowVersion:5,recordVersion:6,canReadCost:false,pairings:[{phoneModelId:201,colorId:8,state:'available',colorSnapshot:{id:8,name:'原报价黑'}}]}));await start('/partner-products?supplierCode=TEST-SUP');expect(api.catalogStyle).not.toHaveBeenCalled();await chooseCatalogStyle();expect(api.catalogStyle).toHaveBeenCalledWith('TEST-SUP',{sourceKind:'selection',recordId:17,rowId:'stable-row'});expect(wrapper.text()).toContain('收到实物再登记收样');await chooseFirstRowModel();await flushPromises();expect(wrapper.get('.selection-pairs').text()).toContain('iPhone 11 · 原报价黑');await wrapper.get('.selection-pairs input[type="checkbox"]').setValue(true);wrapper.get('.selection-row-quantity').getComponent(ElInputNumber).vm.$emit('update:modelValue',2);await flushPromises();await click('保存选样');expect(api.previewCatalogSelection).toHaveBeenCalledWith(expect.objectContaining({items:[expect.objectContaining({rowVersion:5,recordVersion:6,phoneModelIds:[201],sampleQty:2,selectedPairs:[{phoneModelId:201,colorId:8}]})]}));expect(api.addCatalogSelection).toHaveBeenCalledWith(expect.objectContaining({previewToken:'signed-real-pairs',items:[expect.objectContaining({sampleQty:2,phoneModelIds:[201]})]}));expect(api.previewCatalogSelection.mock.invocationCallOrder[0]).toBeLessThan(api.addCatalogSelection.mock.invocationCallOrder[0]);expect(wrapper.find('.catalog-style-row .selection-pairs').exists()).toBe(false)})
  it.each(['list','detail'])('uses the checked toolbar selection without a style-code or reason gate after viewing the %s',async view=>{
    api.catalogStyles.mockResolvedValue(page([row({catalogStyleId:undefined,catalogStyleCode:undefined,catalogStyleLabel:undefined})],{supplier}));api.catalogStyle.mockResolvedValue(row({catalogStyleId:undefined,catalogStyleCode:undefined,catalogStyleLabel:undefined,modelText:'iPhone 11系列',rowVersion:5,recordVersion:6}))
    await start('/partner-products?supplierCode=TEST-SUP'+(view==='detail'?'&sourceKind=selection&recordId=17&rowId=stable-row':''));await chooseCatalogStyle()
    expect(wrapper.find('.selection-model-row').exists()).toBe(true);expect(wrapper.get('.selection-source-models').text()).toContain('iPhone 11系列');expect(wrapper.text()).not.toContain('款式号与报价字段核对');expect(wrapper.find('textarea[placeholder*="原因"]').exists()).toBe(false)
    await chooseFirstRowModel();await flushPromises();await click('保存选样');expect(api.previewCatalogSelection).toHaveBeenCalledWith(expect.objectContaining({items:[expect.objectContaining({rowVersion:5,recordVersion:6,phoneModelIds:[201]})]}));expect(api.addCatalogSelection).toHaveBeenCalledWith(expect.objectContaining({previewToken:'signed-real-pairs'}));expect(api.previewCatalogSelection.mock.invocationCallOrder[0]).toBeLessThan(api.addCatalogSelection.mock.invocationCallOrder[0])
  })
  it.each([false,true])('refreshes only the current list GET after a successful toolbar selection and keeps success when refreshFails=%s',async refreshFails=>{
    const original=row({catalogStyleId:undefined,catalogStyleCode:undefined,catalogStyleLabel:undefined});let reads=0;api.catalogStyles.mockImplementation(async()=>{if(reads++&&refreshFails)throw new Error('目录暂时无法读取');return page([reads>1?row():original],{supplier})});api.catalogStyle.mockResolvedValue(original)
    const router=await start('/partner-products?supplierCode=TEST-SUP&keyword=磁吸&catalogPage=3&catalogSize=20'),before=router.currentRoute.value.fullPath;await chooseCatalogStyle();await chooseFirstRowModel();await click('保存选样');expect(api.addCatalogSelection).toHaveBeenCalledTimes(1);expect(router.currentRoute.value.fullPath).toBe(before);expect(wrapper.get('.catalog-addition').text()).toContain('已保存到选品目录：已有真实选品');expect(api.catalogStyles).toHaveBeenCalledTimes(2);expect(api.catalogStyles).toHaveBeenLastCalledWith({supplierCode:'TEST-SUP',keyword:'磁吸',page:3,size:20});if(refreshFails){expect(wrapper.text()).toContain('选品已保存，目录显示暂未更新');expect(wrapper.text()).not.toContain('保存失败')}else{expect(wrapper.get('.catalog-style-code').text()).toBe('TS-CP-000041');expect(wrapper.get('.catalog-style-name').text()).not.toContain('款式号待补')}
  })
  it('hydrates only explicit cross-page choices with at most three detail requests active and opens the complete set together',async()=>{const values=Array.from({length:5},(_,i)=>row({rowId:'choice-'+i,catalogStyleId:41+i}));api.catalogStyles.mockImplementation(async(params)=>page(params.page===1?values.slice(0,2):values.slice(2),{supplier,totalElements:62}));await start('/partner-products?supplierCode=TEST-SUP');for(const item of wrapper.findAll('.catalog-style-check'))item.getComponent(ElCheckbox).vm.$emit('change',true);wrapper.getComponent(ElPagination).vm.$emit('current-change',2);await flushPromises();for(const item of wrapper.findAll('.catalog-style-check'))item.getComponent(ElCheckbox).vm.$emit('change',true);await flushPromises();let active=0,peak=0;const pending:(()=>void)[]=[];api.catalogStyle.mockImplementation((_supplier,identity)=>new Promise(resolve=>{active++;peak=Math.max(peak,active);pending.push(()=>{active--;resolve({...values.find(value=>value.rowId===identity.rowId)!,pairings:[{phoneModelId:201,colorId:8,state:'available',colorSnapshot:{name:'已核实原色'}}]})})}));await click('选择样品');expect(api.catalogStyle).toHaveBeenCalledTimes(3);expect(wrapper.find('.selection-model-row').exists()).toBe(false);while(pending.length){pending.shift()!();await flushPromises()}expect(peak).toBe(3);expect(api.catalogStyle).toHaveBeenCalledTimes(5);expect(wrapper.findAll('.selection-model-row')).toHaveLength(5);expect(api.addCatalogSelection).not.toHaveBeenCalled();expect(wrapper.get('.selection-batch-picker').attributes('aria-label')).toBe('批量选择系列／型号');await wrapper.get('.selection-batch-picker').trigger('click');await flushPromises();await click('应用到 5 款');expect(wrapper.get('.selection-total').text()).toContain('已选 5 款');for(const record of wrapper.findAll('.selection-model-row'))expect(record.getComponent({name:'PhoneModelMultiSelect'}).props('modelValue')).toEqual([201]);await click('保存选样');expect(api.addCatalogSelection.mock.calls[0][0]).toEqual({supplierCode:'TEST-SUP',requestId:expect.any(String),previewToken:'signed-real-pairs',targetMode:'supplier_directory',items:values.map(({sourceKind,recordId,rowId,rowVersion,recordVersion})=>({sourceKind,recordId,rowId,rowVersion,recordVersion,phoneModelIds:[201]}))})})
  it('keeps the entire original checked range after a partial detail failure and retries it without opening a partial selection',async()=>{const values=Array.from({length:4},(_,i)=>row({rowId:'retry-'+i}));api.catalogStyles.mockResolvedValue(page(values,{supplier}));await start('/partner-products?supplierCode=TEST-SUP');for(const item of wrapper.findAll('.catalog-style-check'))item.getComponent(ElCheckbox).vm.$emit('change',true);await flushPromises();let fail!:(e:Error)=>void;const pending:(()=>void)[]=[];api.catalogStyle.mockImplementation((_supplier,identity)=>new Promise((resolve,reject)=>{if(identity.rowId==='retry-0')fail=reject;else pending.push(()=>resolve(values.find(value=>value.rowId===identity.rowId)))}));await click('选择样品');fail(new Error('本款资料暂时无法读取'));while(pending.length){pending.shift()!();await flushPromises()}await flushPromises();expect(api.catalogStyle).toHaveBeenCalledTimes(3);expect(wrapper.text()).toContain('原勾选已保留');expect(wrapper.text()).toContain('已选 4 款');expect(wrapper.find('.selection-model-row').exists()).toBe(false);api.catalogStyle.mockImplementation(async(_supplier,identity)=>values.find(value=>value.rowId===identity.rowId));await click('重试本次所选款式');expect(wrapper.findAll('.selection-model-row')).toHaveLength(4);expect(wrapper.find('.catalog-selection-load-error').exists()).toBe(false);expect(api.addCatalogSelection).not.toHaveBeenCalled()})
  it('discards cancelled or cross-supplier late detail responses without opening stale choices or surfacing a stale failure',async()=>{await start('/partner-products?supplierCode=TEST-SUP');wrapper.get('.catalog-style-check').getComponent(ElCheckbox).vm.$emit('change',true);await flushPromises();let finish!:(value:CatalogStyle)=>void;api.catalogStyle.mockImplementationOnce(()=>new Promise(resolve=>{finish=resolve}));await click('选择样品');await click('取消本次读取');finish(row());await flushPromises();expect(wrapper.find('.selection-model-row').exists()).toBe(false);expect(wrapper.text()).toContain('已选 1 款');let fail!:(e:Error)=>void;api.catalogStyle.mockImplementationOnce(()=>new Promise((_resolve,reject)=>{fail=reject}));await click('选择样品');await click('更换供应商');fail(new Error('过期供方的内部错误'));await flushPromises();expect(wrapper.find('.selection-model-row').exists()).toBe(false);expect(wrapper.text()).not.toContain('过期供方的内部错误');expect(wrapper.text()).not.toContain('正在读取所选款式');expect(api.addCatalogSelection).not.toHaveBeenCalled()})
  it('starts with supplier choices and never fetches a cross-supplier style list', async () => {
    await start()
    expect(api.catalogSuppliers).toHaveBeenCalledWith({ q: '', page: 1, size: 24 })
    expect(api.catalogStyles).not.toHaveBeenCalled()
    expect(api.catalogStyle).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('共 1 家有款式的供应商')
    expect(wrapper.findAll('.supplier-choice')).toHaveLength(1)
    expect(wrapper.find('.catalog-style-row').exists()).toBe(false)
  })

  it('selects a supplier using its real code and only then loads its styles', async () => {
    const router = await start()
    await wrapper.get('.supplier-choice').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.query.supplierCode).toBe('TEST-SUP')
    expect(api.catalogStyles).toHaveBeenCalledWith({ supplierCode: 'TEST-SUP', keyword: '', page: 1, size: 30 })
    expect(wrapper.text()).toContain('测试供应商 · 供应商产品目录')
  })

  it('searches and pages suppliers without sending any style request', async () => {
    api.catalogSuppliers.mockResolvedValue(page([supplier], { totalElements: 25 }))
    await start()
    await wrapper.get('.supplier-search input').setValue('供应商甲'); await wrapper.get('.supplier-search').trigger('submit'); await flushPromises()
    expect(api.catalogSuppliers).toHaveBeenLastCalledWith({ q: '供应商甲', page: 1, size: 24 })
    wrapper.getComponent(ElPagination).vm.$emit('current-change', 2); await flushPromises()
    expect(api.catalogSuppliers).toHaveBeenLastCalledWith({ q: '供应商甲', page: 2, size: 24 })
    expect(api.catalogStyles).not.toHaveBeenCalled()
  })

  it('keeps a single page of supplier choices compact, with readable names and style counts instead of technical IDs', async () => {
    const choices = ['熊大', '蓝之碧', '赛伯乐', '品俊', '佛山莫凡科技', '热点科技'].map((supplierName, index) => ({ ...supplier, supplierName, supplierCode: `SUP-PRIVATE-${index}`, shortCode: index === 0 ? 'XD' : '', styleCount: [19, 122, 83, 43, 15, 23][index] }))
    api.catalogSuppliers.mockResolvedValue(page(choices))
    await start()
    expect(wrapper.findAll('.supplier-choice')).toHaveLength(6)
    expect(wrapper.text()).toContain('共 6 家有款式的供应商')
    expect(wrapper.text()).not.toContain('SUP-PRIVATE-')
    expect(wrapper.text()).not.toContain('先选择供应商，再查看款式')
    expect(wrapper.findComponent(ElPagination).exists()).toBe(false)
    expect(wrapper.findAll('.supplier-choice').map(button => button.attributes('aria-label'))).toEqual(choices.map(value => `${value.supplierName}，${value.styleCount}款，查看款式`))
    expect(api.catalogStyles).not.toHaveBeenCalled()
  })

  it('explains an empty supplier search without deleting or hiding access to the supplier master', async () => {
    api.catalogSuppliers.mockResolvedValue(page([]))
    const router = await start('/partner-products?supplierQ=没有款式')
    expect(wrapper.text()).toContain('没有找到有款式的供应商')
    expect(wrapper.findAll('.supplier-choice')).toHaveLength(0)
    await click('维护供应商资料')
    expect(router.currentRoute.value.path).toBe('/suppliers')
    expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })

  it('renders image and checkbox as separate columns and emphasizes shared master phone names and codes', async () => {
    await start('/partner-products?supplierCode=TEST-SUP')
    const record = wrapper.get('.catalog-style-row')
    expect(record.find('.catalog-style-photo [data-photo="501"]').exists()).toBe(true)
    expect(record.find('.catalog-style-check').exists()).toBe(true)
    expect(record.get('.catalog-style-photo').find('input[type=checkbox]').exists()).toBe(false)
    expect(record.get('.catalog-style-model strong').text()).toBe('iPhone 11')
    expect(record.get('.catalog-style-model').text()).toContain('0185')
    expect(record.text()).not.toContain('配对待核实')
  })

  it('keeps long material out of the compact directory row while preserving the full text in detail', async () => {
    const material = 'TPU+PC二合一材质，刚柔并济，双层护体，肤感漆。'.repeat(5)
    const value = row({ material, color: '供应商星光白', supplierItemCode: 'FULL-ORIGINAL-CODE', series: '已核实系列' })
    api.catalogStyles.mockResolvedValue(page([value], { supplier })); api.catalogStyle.mockResolvedValue(value)
    await start('/partner-products?supplierCode=TEST-SUP')
    expect(wrapper.get('.catalog-style-config').text()).toContain('供应商星光白')
    expect(wrapper.get('.catalog-style-config').text()).not.toContain(material)
    expect(wrapper.get('.catalog-style-name').text()).toContain('供方款号：FULL-ORIGINAL-CODE')
    await click('查看材质')
    expect(wrapper.get('.style-facts').text()).toContain(material)
    expect(wrapper.text()).toContain('iPhone 11')
  })

  it('uses an accessible image-count badge without an extra line of image text', async () => {
    api.catalogStyles.mockResolvedValue(page([row({ fileDetails: [501,502,503].map(id=>({id,name:`本款${id}.png`,version:'V1',type:'image/png',size:20})) })], { supplier }))
    await start('/partner-products?supplierCode=TEST-SUP')
    expect(wrapper.get('.catalog-photo-open').text()).toBe('3图')
    expect(wrapper.get('.catalog-photo-open').attributes('aria-label')).toBe('查看磁吸支架款的3张图片')
    expect(wrapper.get('.catalog-check-title').text()).toBe('勾选')
    expect(wrapper.get('.catalog-style-actions').findAll('button').map(button=>button.text())).toEqual(['详情', '改名'])
    await wrapper.get('.catalog-photo-open').trigger('click'); await flushPromises()
    expect(api.catalogStyle).toHaveBeenCalledWith('TEST-SUP', expect.objectContaining({rowId:'stable-row'}))
  })

  it('preserves supplier, search and pagination across detail and return, without loading other suppliers', async () => {
    const router = await start('/partner-products?supplierCode=TEST-SUP&keyword=支架&catalogPage=3&catalogSize=20&supplierQ=测试&supplierPage=2')
    await click('详情')
    expect(api.catalogStyle).toHaveBeenCalledWith('TEST-SUP', { sourceKind: 'selection', recordId: 17, rowId: 'stable-row' })
    expect(router.currentRoute.value.query).toMatchObject({ keyword: '支架', catalogPage: '3', supplierPage: '2' })
    await click('返回款式列表')
    expect(api.catalogStyles).toHaveBeenLastCalledWith({ supplierCode: 'TEST-SUP', keyword: '支架', page: 3, size: 20 })
    await click('更换供应商')
    expect(api.catalogSuppliers).toHaveBeenLastCalledWith({ q: '测试', page: 2, size: 24 })
  })

  it('uses server-side search and size changes instead of loading the entire supplier catalog', async () => {
    const router = await start('/partner-products?supplierCode=TEST-SUP&catalogPage=3')
    await wrapper.get('.catalog-search input').setValue('0185'); await click('查询')
    expect(api.catalogStyles).toHaveBeenLastCalledWith({ supplierCode: 'TEST-SUP', keyword: '0185', page: 1, size: 30 })
    wrapper.getComponent(ElPagination).vm.$emit('size-change', 50); await flushPromises()
    expect(router.currentRoute.value.query.catalogSize).toBe('50')
    expect(api.catalogStyles).toHaveBeenLastCalledWith({ supplierCode: 'TEST-SUP', keyword: '0185', page: 1, size: 50 })
  })

  it('keeps explicit checked rows across pages but clears them when choosing another supplier', async () => {
    await start('/partner-products?supplierCode=TEST-SUP')
    wrapper.get('.catalog-style-check').getComponent(ElCheckbox).vm.$emit('change', true); await flushPromises()
    expect(wrapper.text()).toContain('已选 1 款（跨页保留）')
    api.catalogStyles.mockResolvedValue(page([row({ rowId: 'page-two' })], { supplier, totalElements: 62 }))
    wrapper.getComponent(ElPagination).vm.$emit('current-change', 2); await flushPromises()
    expect(wrapper.text()).toContain('已选 1 款（跨页保留）')
    await click('更换供应商'); await wrapper.get('.supplier-choice').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('已选 0 款（跨页保留）')
  })

  it('shows supplier and detail failures with retry, without introducing a write', async () => {
    api.catalogSuppliers.mockRejectedValueOnce(new Error('读取供应商失败'))
    await start(); expect(wrapper.text()).toContain('读取供应商失败'); await click('重新加载')
    expect(wrapper.find('.supplier-choice').exists()).toBe(true)
    expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })

  it('keeps suppliers without styles honest and links to existing range maintenance instead of implying an import', async () => {
    api.catalogStyles.mockResolvedValue(page([], { supplier }))
    const router = await start('/partner-products?supplierCode=TEST-SUP')
    expect(wrapper.text()).toContain('该供应商还没有目录款式')
    expect(wrapper.text()).toContain('导入供应商资料')
    expect(wrapper.text()).not.toContain('去选品工作台录入')
    await click('维护型号与颜色范围')
    expect(router.currentRoute.value.path).toBe('/suppliers')
    expect(router.currentRoute.value.query).toMatchObject({ code: 'TEST-SUP', from: '/partner-products?supplierCode=TEST-SUP' })
    expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })

  it('ignores a late supplier response after the user navigates into a supplier', async () => {
    let resolve!: (value: unknown) => void
    api.catalogSuppliers.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const router = await start()
    await router.push('/partner-products?supplierCode=TEST-SUP'); await flushPromises()
    resolve(page([{ ...supplier, supplierName: '过期响应' }])); await flushPromises()
    expect(wrapper.text()).not.toContain('过期响应')
    expect(wrapper.find('.catalog-style-row').exists()).toBe(true)
  })

  it('keeps old PP deep links in the existing product workflow with no catalog request', async () => {
    await start('/partner-products?id=7&from=%2Fsamples%3Fid%3D2', true)
    expect(wrapper.get('.existing-workspace').text()).toBe('现有产品办理')
    expect(api.catalogSuppliers).not.toHaveBeenCalled()
    expect(api.catalogStyles).not.toHaveBeenCalled()
  })

  it('keeps the current product ledger as a deliberate secondary view', async () => {
    await start('/partner-products?view=products', true)
    expect(wrapper.find('.existing-workspace').exists()).toBe(true)
    expect(api.catalogSuppliers).not.toHaveBeenCalled()
    await click('供应商款式')
    expect(wrapper.find('.supplier-choice').exists()).toBe(true)
  })
})

describe('unified supplier style detail and permissions', () => {
  const detailPath = '/partner-products?supplierCode=TEST-SUP&sourceKind=selection&recordId=17&rowId=stable-row'

  it('opens single-style model maintenance using its independent legacy-source permission and refreshes only the returned detail after confirmation',async()=>{api.catalogStyle.mockResolvedValue(row({canWrite:false,canMaintainModelSheet:true,sourcePurpose:'catalog_source'}));const router=await start(detailPath),path=router.currentRoute.value.fullPath;expect(wrapper.findAll('button').some(button=>button.text()==='型号表核对')).toBe(true);await click('型号表核对');const dialog=wrapper.getComponent({name:'CatalogModelSheetDialog'});expect(dialog.props('row')).toMatchObject({sourceKind:'selection',recordId:17,rowId:'stable-row',canWrite:false});dialog.vm.$emit('saved',{style:row({canWrite:false,canMaintainModelSheet:true,modelText:'已核对原型号表',phoneModelIds:[],phones:[]}),changed:true,replayed:false});await flushPromises();expect(wrapper.find('.model-sheet-owner').exists()).toBe(false);expect(wrapper.get('.model-panel').text()).toContain('已核对原型号表');expect(wrapper.text()).toContain('本款型号表已更新');expect(router.currentRoute.value.fullPath).toBe(path);expect(api.catalogStyle).toHaveBeenCalledTimes(1);expect(api.addCatalogSelection).not.toHaveBeenCalled()})

  it('keeps confirmed manual model links clear while retaining all original matching diagnostics and the engineering note inside collapsed review details',async()=>{const value=row({supplierModelEntries:[{supplierIndex:21,rawBrandGroup:'Apple',rawModelText:'原14ProMax写法',rawStatus:'开模中',rawNote:'原供方备注',phoneModelIds:[201],phones,matchMethod:'manual',matchState:'manual',matchNote:'工程已按原图确认现有组合资料',issues:['型号存在多个独立资料，请核对'],automaticIssues:['旧自动解析问题保留']},{supplierIndex:97,rawBrandGroup:'原分组',rawModelText:'尚未确认的原文',rawStatus:'试模',rawNote:'原备注仍保留',phoneModelIds:[],phones:[],issues:['真正失效的编码关联仍需核对']}],phoneMatchIssues:['原关联编码已停用']});const before=JSON.stringify(value);api.catalogStyle.mockResolvedValue(value);await start(detailPath);const records=wrapper.findAll('.supplier-model-entry');expect(records[0].get('.supplier-model-links>span').text()).toContain('iPhone 11');expect(records[0].text()).toContain('开模中');expect(records[0].text()).toContain('原供方备注');expect(records[0].find('.supplier-model-links>.model-exceptions').exists()).toBe(false);const review=records[0].get('.model-review-notes');expect((review.element as HTMLDetailsElement).open).toBe(false);expect(review.get('summary').text()).toBe('核对说明');(review.element as HTMLDetailsElement).open=true;await review.trigger('toggle');expect(review.text()).toContain('工程已按原图确认现有组合资料');expect(review.text()).toContain('型号存在多个独立资料，请核对');expect(review.text()).toContain('旧自动解析问题保留');expect(records[1].get('.supplier-model-links>p').text()).toBe('原文待关联');expect(records[1].get('.model-review-notes').text()).toContain('真正失效的编码关联仍需核对');expect((wrapper.get('.model-diagnostics').element as HTMLDetailsElement).open).toBe(false);expect(wrapper.get('.model-diagnostics').text()).toContain('原关联编码已停用');expect(JSON.stringify(value)).toBe(before);expect(api.addCatalogSelection).not.toHaveBeenCalled()})

  it('moves long source notes into the existing closed source-and-version section without crowding out the first-screen images or model table',async()=>{const notes='原件SHA256：保留完整校验值\n来源键：供应商原件／表格位置\n'.repeat(5);api.catalogStyle.mockResolvedValue(row({notes}));await start(detailPath);const detail=wrapper.get('.supplier-style-detail');expect(detail.get('.catalog-panel').classes()).toContain('catalog-materials');expect(detail.get('.catalog-materials').text()).toContain('本款原图.png');expect(wrapper.find('.detail-heading+.style-facts+.style-notes').exists()).toBe(false);expect(wrapper.get('.catalog-source').attributes('open')).toBeUndefined();expect(wrapper.get('.catalog-source .style-notes').text()).toBe(notes.trim());expect(wrapper.get('.model-panel').text()).not.toContain('原件SHA256');expect(detail.findAll('.style-notes')).toHaveLength(1)})

  it('shows every original supplier model entry with its own status, note, source image and multi-phone mapping to a readonly user, with search and pagination',async()=>{const entries=Array.from({length:116},(_,index)=>({supplierIndex:index+1,rawBrandGroup:'原分组',rawModelText:`原型号${index}/原组合`,rawStatus:['现货','开模中','试模'][index%3],rawNote:`原备注${index}`,phoneModelIds:[index+201],phones:[{...phones[0],phoneModelId:index+201,modelName:`现有型号${index}`,phoneModelCode:String(index).padStart(4,'0')}]}));entries[0].phones.push({...phones[0],phoneModelId:999,modelName:'另一个已有组合成员',phoneModelCode:'0999'});api.catalogStyle.mockResolvedValue(row({canWrite:false,canMaintainModelSheet:false,supplierModelEntries:entries,modelSheetRefs:[{id:88,type:'file'}],modelSheetFileDetails:[{id:88,name:'本款原型号表.png',version:'V1',type:'image/png',size:20}]}));await start(detailPath);expect(wrapper.findAll('.supplier-model-entry')).toHaveLength(20);expect(wrapper.findAll('.supplier-model-entry')[0].text()).toContain('另一个已有组合成员');expect(wrapper.get('.model-sheet-originals').text()).toContain('本款原型号表.png');expect(wrapper.get('.model-panel').text()).toContain('116 条原记录');expect(wrapper.findAll('button').some(button=>button.text()==='型号表核对')).toBe(false);wrapper.getComponent(ElPagination).vm.$emit('update:current-page',6);await flushPromises();expect(wrapper.findAll('.supplier-model-entry')).toHaveLength(16);expect(wrapper.findAll('.supplier-model-entry')[15].text()).toContain('原备注115');await wrapper.get('[aria-label="搜索本款手机型号"]').setValue('试模');expect(wrapper.findAll('.supplier-model-entry')).toHaveLength(20);expect(wrapper.findAll('.supplier-model-entry').every(entry=>entry.text().includes('试模'))).toBe(true);await wrapper.get('[aria-label="搜索本款手机型号"]').setValue('0115');expect(wrapper.findAll('.supplier-model-entry')).toHaveLength(1);expect(wrapper.get('.supplier-model-entry').text()).toContain('原型号115/原组合');expect(api.addCatalogSelection).not.toHaveBeenCalled()})
  it('shows only this style images and attachments, with source originals separately protected', async () => {
    api.catalogStyle.mockResolvedValue(row({ fileDetails: [row().fileDetails[0], { id: 502, name: '本款参数.pdf', version: 'V1', type: 'application/pdf', size: 99 }], sourceOriginals: [{ id: 901, name: '供方完整报价原件.xls', type: 'application/vnd.ms-excel', version: 'V2', size: 200 }], sourceSheet: '供方目录', sourcePosition: 'B4:F4', notes: '真实配置备注' }))
    await start(detailPath)
    expect(wrapper.findAll('.test-files')).toHaveLength(2)
    expect(wrapper.get('.catalog-materials').text()).toContain('本款原图.png')
    expect(wrapper.get('.catalog-materials').text()).toContain('本款参数.pdf')
    expect(wrapper.get('.catalog-materials').text()).not.toContain('供方完整报价原件')
    expect(wrapper.get('.source-originals').text()).toContain('供方完整报价原件.xls')
    expect(wrapper.get('.catalog-source').attributes('open')).toBeUndefined()
    expect(wrapper.text()).toContain('0185')
    expect(wrapper.text()).toContain('只勾本次需要的型号，不必重新匹配')
  })

  it('searches and pages a long existing phone range without opening another selector', async () => {
    const many = Array.from({ length: 39 }, (_, index) => ({ ...phones[0], phoneModelId: index + 1, modelName: `iPhone ${index + 1}`, phoneModelCode: String(index).padStart(4, '0') }))
    api.catalogStyle.mockResolvedValue(row({ phones: many, modelText: 'iPhone11到18（mini除外）' }))
    await start(detailPath)
    expect(wrapper.findAll('.catalog-phone')).toHaveLength(12)
    wrapper.getComponent(ElPagination).vm.$emit('update:current-page', 2); await flushPromises()
    expect(wrapper.findAll('.catalog-phone')[0].text()).toContain('iPhone 13')
    await wrapper.get('[aria-label="搜索本款手机型号"]').setValue('0038')
    expect(wrapper.findAll('.catalog-phone')).toHaveLength(1)
    expect(wrapper.get('.catalog-phone').text()).toContain('iPhone 39')
    expect(wrapper.find('.phone-model-trigger').exists()).toBe(false)
  })

  it('does not invent prices, currencies or model identities when source fields are absent', async () => {
    api.catalogStyle.mockResolvedValue(row({ unitPrice: null, currency: '', phoneModelIds: [], phones: [], modelText: '', priceNote: '' }))
    await start(detailPath)
    expect(wrapper.get('.quote-panel').text()).toContain('未报价')
    expect(wrapper.text()).toContain('型号资料待关联')
    expect(wrapper.text()).not.toContain('CNY')
  })

  it.each((['list','detail'] as const).flatMap(view=>([undefined,'supplier','unresolved','unspecified'] as const).map(mode=>({view,mode}))))('uses the backend scope meaning for an empty $view model field: $mode',async({view,mode})=>{
    const value=row({phoneScopeMode:mode,phoneModelIds:[],phones:[],modelText:''});api.catalogStyles.mockResolvedValue(page([value],{supplier}));api.catalogStyle.mockResolvedValue(value)
    await start(view==='detail'?detailPath:'/partner-products?supplierCode=TEST-SUP')
    const text=wrapper.get(view==='detail'?'.model-panel .muted':'.catalog-style-model strong').text();expect(text).toBe(mode==='unspecified'?'供应商未注明型号':'型号资料待关联');expect(wrapper.text()).not.toContain('原资料未提供手机型号');expect(wrapper.text()).not.toContain('已匹配现有手机资料')
    if(mode!=='unspecified')expect(wrapper.text()).not.toContain('供应商未注明型号')
  })

  it.each((['list','detail'] as const).flatMap(view=>([undefined,'supplier','unresolved','unspecified'] as const).map(mode=>({view,mode}))))('preserves real supplier model text in the $view instead of replacing it with a scope fallback: $mode',async({view,mode})=>{
    const value=row({phoneScopeMode:mode,phoneModelIds:[],phones:[],modelText:'原件型号表：iPhone 11–18，mini除外'});api.catalogStyles.mockResolvedValue(page([value],{supplier}));api.catalogStyle.mockResolvedValue(value)
    await start(view==='detail'?detailPath:'/partner-products?supplierCode=TEST-SUP');expect(wrapper.get(view==='detail'?'.model-panel .muted':'.catalog-style-model strong').text()).toBe(value.modelText);expect(wrapper.text()).not.toContain('型号资料待关联');expect(wrapper.text()).not.toContain('供应商未注明型号')
  })

  it('shows all actual configuration quotes using the backend priceOptions contract instead of inventing a minimum price', async () => {
    api.catalogStyle.mockResolvedValue(row({ unitPrice: undefined, currency: '', priceOptions: [{ label: '磁吸款', unitPrice: '8.00', currency: 'CNY', moq: '每色20个', packaging: '供方普通包装' }, { label: '旋转支架款', unitPrice: '12.00', currency: '', priceNote: '币种原件未注明' }], thickness: '1.5 mm', features: '原件注明支架功能', leadTimeNote: '本单另议', sampleFeeNote: '大货后按约抵扣' }))
    await start(detailPath)
    expect(wrapper.findAll('.quote-options article')).toHaveLength(2)
    expect(wrapper.get('.quote-options').text()).toContain('磁吸款报价8.00币种CNY')
    expect(wrapper.get('.quote-options').text()).toContain('旋转支架款报价12.00币种未确认')
    expect(wrapper.get('.quote-options').text()).toContain('每色20个')
    expect(wrapper.text()).toContain('1.5 mm')
    expect(wrapper.text()).toContain('大货后按约抵扣')
  })

  it('summarizes multiple actual quotes in the directory without presenting the first as the whole style price', async () => {
    api.catalogStyles.mockResolvedValue(page([row({ priceOptions: [{ label: '配置A', unitPrice: '5.00', currency: 'CNY' }, { label: '配置B', unitPrice: '8.00', currency: 'CNY' }] })], { supplier }))
    await start('/partner-products?supplierCode=TEST-SUP')
    expect(wrapper.get('.catalog-style-price strong').text()).toBe('多种报价')
    expect(wrapper.get('.catalog-style-price').text()).not.toContain('12.50')
  })

  it('suppresses the entire cost area and restricted originals for non-cost users even if stale fields are supplied', async () => {
    api.catalogStyle.mockResolvedValue(row({ canReadCost: false, sourceOriginals: [{ id: 901, name: '受限原件.xls', version: 'V1', type: 'application/vnd.ms-excel', size: 99 }] }))
    await start(detailPath)
    expect(wrapper.find('.quote-panel').exists()).toBe(false)
    expect(wrapper.find('.source-originals').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('12.50')
    expect(wrapper.text()).not.toContain('受限原件')
  })

  it('keeps read-only browsing without selection controls or costs', async () => {
    api.catalogStyles.mockResolvedValue(page([row()], { supplier, canWrite: false, canReadCost: false }))
    await start('/partner-products?supplierCode=TEST-SUP')
    expect(wrapper.find('.catalog-style-check').exists()).toBe(false)
    expect(wrapper.find('.catalog-style-price').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('加入选品')
    expect(wrapper.text()).not.toContain('12.50')
  })

  it('opens the real associated product while keeping the return location', async () => {
    api.catalogStyle.mockResolvedValue(row({ productId: 81, productCode: 'PP-000081' }))
    const router = await start(detailPath)
    await click('关联我方产品 PP-000081')
    expect(router.currentRoute.value.query).toMatchObject({ id: '81', from: detailPath })
  })
})

describe('catalog compact responsive layout contract',()=>{
  it('wraps separate labelled fields and complete actions at narrow container widths, retaining readable images and models',()=>{
    const wide=catalogSource.split('@container(max-width:1150px)')[0]
    const medium=catalogSource.split('@container(max-width:1150px)')[1].split('@container(max-width:480px)')[0]
    expect(wide).toContain('min-height:52px')
    expect(wide).toContain('width:44px;height:44px')
    expect(wide).toContain('font-size:15px;font-weight:600')
    expect(medium).toContain('grid-template-rows:auto auto')
    expect(medium).toContain('.catalog-style-actions{grid-column:7;grid-row:1/3;')
    expect(medium).toContain('.catalog-style-name{grid-column:3/5;grid-row:1}')
    expect(medium).toContain('.catalog-style-model{grid-column:5/7;grid-row:1}')
    expect(medium).toContain('.catalog-style-row>.catalog-style-material,.catalog-style-row>.catalog-style-moq-scope{display:none!important}')
    expect(wide).toContain('.catalog-style-actions :deep(.el-button){flex:0 0 auto;')
    expect(catalogSource).toContain('class="catalog-style-currency"')
    expect(catalogSource).toContain('class="catalog-style-moq-scope"')
  })
  it('suppresses duplicate readonly hints only inside source originals, leaving normal product evidence untouched',()=>{
    expect(detailSource).toContain('.source-originals :deep(.partner-files>header),.source-originals :deep(.partner-files>.no-photo){display:none}')
    expect(detailSource).toContain('class="catalog-panel catalog-materials"')
    expect(detailSource).toContain(':files="row.fileDetails||[]"')
    expect(detailSource).not.toContain('.catalog-materials :deep(.partner-files>header)')
  })
  it('preserves the explicit workbench directory while searching, paging, choosing suppliers and opening style details',async()=>{
    api.catalogSuppliers.mockResolvedValue(page([supplier],{totalElements:25}))
    const router=await start('/partner-products?selectionId=77&targetVersion=8&from=%2Fpartner-selections%3Fid%3D77')
    const context={selectionId:'77',targetVersion:'8',from:'/partner-selections?id=77'}
    await wrapper.get('.supplier-search input').setValue('真实');await wrapper.get('.supplier-search').trigger('submit');await flushPromises();expect(router.currentRoute.value.query).toMatchObject(context)
    wrapper.getComponent(ElPagination).vm.$emit('current-change',2);await flushPromises();expect(router.currentRoute.value.query).toMatchObject(context)
    await wrapper.get('.supplier-choice').trigger('click');await flushPromises();expect(router.currentRoute.value.query).toMatchObject({...context,supplierCode:'TEST-SUP'})
    wrapper.getComponent(ElPagination).vm.$emit('current-change',2);await flushPromises();await click('详情');expect(router.currentRoute.value.query).toMatchObject({...context,catalogPage:'2'})
    await click('返回款式列表');expect(router.currentRoute.value.query).toMatchObject({...context,catalogPage:'2'})
    await click('更换供应商');expect(router.currentRoute.value.query).toMatchObject(context)
    expect(api.selectionPage).not.toHaveBeenCalled();expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })
  it('directly saves to the exact workbench context with one action and no destination lookup',async()=>{
    const router=await start('/partner-products?supplierCode=TEST-SUP&selectionId=77&targetVersion=8');await chooseCatalogStyle();await chooseFirstRowModel();expect(wrapper.find('.selection-targets').exists()).toBe(false);expect(wrapper.text()).not.toContain('选择我方清单')
    await click('保存选样');const intent={supplierCode:'TEST-SUP',targetMode:'supplier_directory',targetSelectionId:77,targetVersion:8};expect(api.previewCatalogSelection).toHaveBeenCalledWith(expect.objectContaining(intent));expect(api.addCatalogSelection).toHaveBeenCalledWith(expect.objectContaining({...intent,previewToken:'signed-real-pairs'}));expect(api.selectionPage).not.toHaveBeenCalled();expect(wrapper.get('.catalog-addition').text()).toContain('已保存到选品目录')
    await click('打开选品目录');expect(router.currentRoute.value.query.id).toBe('77')
  })
  it('keeps a cross-supplier explicit context for the server to reject instead of silently redirecting the choice',async()=>{
    api.previewCatalogSelection.mockResolvedValue({canAdd:false,items:[],messages:['原选品目录不属于本供应商'],addedCount:0,reusedCount:0,newPhoneCount:0})
    const router=await start('/partner-products?selectionId=77&targetVersion=8');await wrapper.get('.supplier-choice').trigger('click');await flushPromises();await chooseCatalogStyle();await chooseFirstRowModel();await click('保存选样');expect(api.previewCatalogSelection).toHaveBeenCalledWith(expect.objectContaining({targetSelectionId:77,targetVersion:8,targetMode:'supplier_directory'}));expect(wrapper.text()).toContain('原选品目录不属于本供应商');expect(router.currentRoute.value.query.selectionId).toBe('77');expect(api.addCatalogSelection).not.toHaveBeenCalled();expect(api.selectionPage).not.toHaveBeenCalled()
  })
  it.each(['selectionId=oops','selectionId=0','selectionId=77&targetVersion=bad'])('rejects malformed workbench context rather than dropping it: %s',async context=>{await start('/partner-products?supplierCode=TEST-SUP&'+context);await chooseCatalogStyle();expect(wrapper.text()).toContain('原选品目录链接无效');expect(wrapper.find('.selection-model-row').exists()).toBe(false);expect(api.catalogStyle).not.toHaveBeenCalled();expect(api.addCatalogSelection).not.toHaveBeenCalled()})

  it('keeps the original supplier and chosen phones visible while an unknown save is reconciled instead of navigating to another supplier',async()=>{api.addCatalogSelection.mockRejectedValueOnce({code:'ECONNABORTED'}).mockResolvedValue({selection:{id:77,name:'真实选择目录',version:9},addedCount:1,reusedCount:0,rowIds:['new-row'],replayed:true});const router=await start('/partner-products?supplierCode=TEST-SUP&selectionId=77&targetVersion=8');await chooseCatalogStyle();await chooseFirstRowModel();await click('保存选样');const original=api.addCatalogSelection.mock.calls[0][0];expect(wrapper.text()).toContain('保存结果待核对');await router.push('/partner-products?supplierCode=OTHER&selectionId=88&targetVersion=9');await flushPromises();expect(router.currentRoute.value.query.supplierCode).toBe('TEST-SUP');expect(router.currentRoute.value.query.selectionId).toBe('77');expect(wrapper.get('.selection-intro').text()).toContain('测试供应商');expect(wrapper.find('.selection-model-row').exists()).toBe(true);await click('核对保存结果');expect(api.previewCatalogSelection).toHaveBeenCalledTimes(1);expect(api.addCatalogSelection.mock.calls[1][0]).toEqual(original);await click('更换供应商');expect(router.currentRoute.value.query.supplierCode).toBeUndefined()})

  it('can keep selecting into the same exact workbench directory after a successful save without carrying a stale client target version',async()=>{api.previewCatalogSelection.mockResolvedValueOnce({canAdd:true,previewToken:'first',targetSelectionId:77,targetVersion:8,items:[],messages:[]}).mockResolvedValue({canAdd:true,previewToken:'second',targetSelectionId:77,targetVersion:9,items:[],messages:[]});const router=await start('/partner-products?supplierCode=TEST-SUP&selectionId=77');await chooseCatalogStyle();await chooseFirstRowModel();await click('保存选样');await chooseCatalogStyle();await chooseFirstRowModel();await click('保存选样');expect(api.previewCatalogSelection).toHaveBeenCalledTimes(2);expect(api.addCatalogSelection).toHaveBeenCalledTimes(2);for(const call of api.addCatalogSelection.mock.calls){expect(call[0]).toMatchObject({targetSelectionId:77,targetMode:'supplier_directory'});expect(call[0]).not.toHaveProperty('targetVersion')}expect(api.addCatalogSelection.mock.calls.map(call=>call[0].previewToken)).toEqual(['first','second']);expect(router.currentRoute.value.query.selectionId).toBe('77');expect(api.selectionPage).not.toHaveBeenCalled()})

  it('offers one checked-style selection entry above the list and keeps row and detail views free of duplicate add actions',async()=>{const router=await start('/partner-products?supplierCode=TEST-SUP');expect(wrapper.findAll('button').filter(button=>button.text()==='选择样品')).toHaveLength(1);expect(wrapper.text()).not.toContain('加入选品');expect(wrapper.get('.catalog-style-actions').findAll('button').map(button=>button.text())).toEqual(['详情','改名']);expect(wrapper.findAll('button').find(button=>button.text()==='选择样品')!.attributes('disabled')).toBeDefined();await click('详情');expect(wrapper.find('.detail-scroll').exists()).toBe(true);expect(wrapper.text()).not.toContain('加入选品');expect(wrapper.findAll('button').filter(button=>button.text()==='选择样品')).toHaveLength(0);expect(wrapper.get('.test-files').text()).toContain('本款原图.png');expect(wrapper.get('.supplier-style-detail').text()).toContain('iPhone 11');await click('返回款式列表');expect(router.currentRoute.value.query.supplierCode).toBe('TEST-SUP');await chooseCatalogStyle();expect(wrapper.findAll('.selection-model-row')).toHaveLength(1);expect(api.addCatalogSelection).not.toHaveBeenCalled();expect(api.previewCatalogSelection).not.toHaveBeenCalled()})

})
