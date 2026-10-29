import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElCheckbox, ElPagination, ElRadioGroup, ElSelect } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ partnerOptions: vi.fn(), catalogSources: vi.fn(), createCatalogSource: vi.fn(), catalogSourceDetail: vi.fn(), previewCatalogSourcePurpose: vi.fn(), changeCatalogSourcePurpose: vi.fn(), fileBlob: vi.fn(), saveBlob: vi.fn() }))
vi.mock('@/api/modules/partnerCatalog', () => api)
vi.mock('@/api/modules/partner', () => ({ ...api, newRequestId: () => crypto.randomUUID() }))
vi.mock('../selection/SelectionFileTasks.vue', () => ({ default: { name: 'SelectionFileTasks', props: ['id', 'context', 'supplierCode', 'modelValue', 'canWrite'], template: '<section v-if="modelValue" class="existing-file-workflow"><slot name="source-files"/>原有分步导入、图片与失败重试</section>' } }))
import SourceImport from '../catalog/CatalogSourceImport.vue'
let wrapper: VueWrapper
const source = { id: 70, code: 'SQ-70', name: '本次供应商资料', kind: 'supplier_quote', sourceKind: 'quote', supplierCode: 'SUP-ONE', supplierName: '已有供应商', version: 1, rowCount: 0, purpose: 'catalog_source', canImport: true, readOnly: false }
const history = { ...source, id: 40, code: 'SL-40', kind: 'selection', sourceKind: 'selection', name: '历史全量供应商资料', version: 5, rowCount: 43, purpose: 'my_selection', canImport: false, readOnly: true }
const page = (content = [source], totalElements = content.length) => ({ content, totalElements, page: 1, size: 20, totalPages: Math.ceil(totalElements / 20), canWrite: true, canReadCost: true })
beforeEach(() => {
  vi.clearAllMocks(); api.partnerOptions.mockResolvedValue({ suppliers: [{ code: 'SUP-ONE', name: '已有供应商' }, { code: 'SUP-EMPTY', name: '尚无款式的供应商' }] })
  api.catalogSources.mockResolvedValue(page()); api.createCatalogSource.mockResolvedValue(source); api.catalogSourceDetail.mockResolvedValue(source)
  api.previewCatalogSourcePurpose.mockResolvedValue({ ...history, targetPurpose: 'catalog_source', canChange: true, previewToken: 'signed-purpose', messages: [] })
  api.changeCatalogSourcePurpose.mockResolvedValue({ ...history, purpose: 'catalog_source' })
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })
async function start(extra = {}) { wrapper = mount(SourceImport, { props: { modelValue: true, supplierCode: 'SUP-ONE', canWrite: true, ...extra }, global: { plugins: [ElementPlus], stubs: { ElDialog: { props: ['modelValue'], template: '<section v-if="modelValue"><slot/><slot name="footer"/></section>' } } } }); await flushPromises() }
const button = (text: string) => wrapper.findAll('button').find(button => button.text() === text)!
async function click(text: string) { if(text==='新建资料并上传'){expect(button(text).attributes('type')).toBe('submit');await wrapper.get('.source-create').trigger('submit')}else await button(text).trigger('click'); await flushPromises() }
async function prepareHistory() { wrapper.getComponent(ElRadioGroup).vm.$emit('update:modelValue', 'historical'); await flushPromises(); await click('核对用途'); await wrapper.get('[aria-label="资料用途整理原因"]').setValue('核对原件为完整候选，未作我方选择'); await flushPromises() }

describe('supplier source entry reuses the existing import workflow', () => {
  it('shows a failed original download inside the currently visible file workflow and retries the same real file',async()=>{
    const file={id:901,name:'真实完整原件.xlsx',version:'V1',type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',size:100},blob=new Blob(['original'])
    api.catalogSourceDetail.mockResolvedValue({...source,files:[file]});api.fileBlob.mockRejectedValueOnce(new Error('网络暂时中断')).mockResolvedValue(blob)
    await start();await click('继续上传／导入');await click('真实完整原件.xlsx · V1')
    const visible=wrapper.get('.existing-file-workflow');expect(visible.get('.source-download-error').text()).toContain('原件下载失败');expect(visible.get('.source-download-error').text()).toContain(file.name);expect(visible.get('.source-download-error').text()).toContain('网络暂时中断');expect(api.saveBlob).not.toHaveBeenCalled()
    await visible.get('[aria-label="重试下载真实完整原件.xlsx"]').trigger('click');await flushPromises();expect(api.fileBlob.mock.calls).toEqual([[901],[901]]);expect(api.saveBlob).toHaveBeenCalledWith(blob,file.name);expect(wrapper.find('.source-download-error').exists()).toBe(false);expect(visible.text()).toContain(file.name)
  })
  it('offers every authorised existing supplier, including those hidden from the nonempty directory, without creating one', async () => {
    await start({ supplierCode: '' }); expect(wrapper.getComponent(ElSelect).findAllComponents({ name: 'ElOption' }).map(option => option.props('label'))).toEqual(['已有供应商', '尚无款式的供应商'])
    expect(api.createCatalogSource).not.toHaveBeenCalled(); expect(button('新建资料并上传').attributes('disabled')).toBeDefined();expect(api.catalogSources).not.toHaveBeenCalled();expect(wrapper.text()).toContain('请先选择供应商，再查看或导入资料');expect(wrapper.find('.el-alert--error').exists()).toBe(false)
  })
  it('loads source records only after a supplier is chosen and keeps an empty historical tab as a prompt, not an error',async()=>{
    await start({supplierCode:''});wrapper.getComponent(ElRadioGroup).vm.$emit('update:modelValue','historical');await flushPromises();expect(api.catalogSources).not.toHaveBeenCalled();expect(wrapper.text()).toContain('请先选择供应商，再查看或导入资料')
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue','SUP-ONE');await flushPromises();expect(api.catalogSources).toHaveBeenCalledWith({supplierCode:'SUP-ONE',purpose:'historical',page:1,size:20});expect(wrapper.find('.source-record').exists()).toBe(true)
    const calls=api.catalogSources.mock.calls.length;wrapper.getComponent(ElSelect).vm.$emit('update:modelValue','');await flushPromises();expect(api.catalogSources).toHaveBeenCalledTimes(calls);expect(wrapper.find('.source-record').exists()).toBe(false);expect(wrapper.text()).toContain('请先选择供应商，再查看或导入资料')
  })
  it('invalidates a pending supplier response when the selection is cleared, without restoring records or old errors',async()=>{
    let resolve!:(data:ReturnType<typeof page>)=>void,reject!:(error:Error)=>void;api.catalogSources.mockImplementation(()=>new Promise((done,fail)=>{resolve=done;reject=fail}));await start();expect(api.catalogSources).toHaveBeenCalled()
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue','');await flushPromises();const calls=api.catalogSources.mock.calls.length;resolve(page());await flushPromises();expect(api.catalogSources).toHaveBeenCalledTimes(calls);expect(wrapper.find('.source-record').exists()).toBe(false);expect(wrapper.text()).toContain('请先选择供应商，再查看或导入资料');expect(wrapper.find('.el-alert--error').exists()).toBe(false)
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue','SUP-ONE');await flushPromises();wrapper.getComponent(ElSelect).vm.$emit('update:modelValue','');await flushPromises();reject(new Error('已清空供应商的旧错误'));await flushPromises();expect(wrapper.text()).not.toContain('已清空供应商的旧错误');expect(wrapper.text()).toContain('请先选择供应商，再查看或导入资料')
  })
  it('creates only a versioned supplier source before entering the shared file dialog, never a selection or product', async () => {
    await start(); await wrapper.get('[aria-label="供应商资料名称"]').setValue('供应商最新目录'); await click('新建资料并上传')
    expect(api.createCatalogSource).toHaveBeenCalledWith({ supplierCode: 'SUP-ONE', name: '供应商最新目录', requestId: expect.any(String) })
    expect(wrapper.getComponent({ name: 'SelectionFileTasks' }).props()).toMatchObject({ id: 70, context: 'catalog', supplierCode: 'SUP-ONE', canWrite: true, modelValue: true })
    expect(wrapper.text()).toContain('原有分步导入、图片与失败重试')
  })
  it('retains failed creation inputs and reuses its request identity instead of making duplicate sources', async () => {
    api.createCatalogSource.mockRejectedValueOnce(new Error('创建回应超时')).mockResolvedValue(source); await start(); await click('新建资料并上传')
    expect(wrapper.text()).toContain('创建回应超时'); const input = api.createCatalogSource.mock.calls[0][0]; await click('新建资料并上传'); expect(api.createCatalogSource.mock.calls[1][0]).toEqual(input)
  })
  it('loads source detail before resuming and preserves a marked historical source as readonly with original downloads', async () => {
    api.catalogSources.mockResolvedValue(page([{ ...source, kind: 'selection', sourceKind: 'selection', canImport: false, readOnly: true }]))
    api.catalogSourceDetail.mockResolvedValue({ ...source, kind: 'selection', sourceKind: 'selection', canImport: false, readOnly: true, files: [{ id: 901, name: '真实完整原件.xlsx', version: 'V1', type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', size: 100 }] })
    await start(); await click('查看历史导入'); expect(api.catalogSourceDetail).toHaveBeenCalledWith(70)
    expect(wrapper.getComponent({ name: 'SelectionFileTasks' }).props('canWrite')).toBe(false); expect(wrapper.text()).toContain('历史来源只读'); expect(wrapper.text()).toContain('真实完整原件.xlsx')
  })
  it('requires an explicit reason, difference preview and acknowledgment for one chosen historical record', async () => {
    api.catalogSources.mockResolvedValue(page([history])); await start(); await prepareHistory(); expect(api.changeCatalogSourcePurpose).not.toHaveBeenCalled(); await click('预览用途调整')
    expect(api.previewCatalogSourcePurpose).toHaveBeenCalledWith(40, { version: 5, purpose: 'catalog_source', reason: '核对原件为完整候选，未作我方选择' }); expect(button('确认整理为供应商资料').attributes('disabled')).toBeDefined()
    wrapper.getComponent(ElCheckbox).vm.$emit('update:modelValue', true); await flushPromises(); await click('确认整理为供应商资料')
    expect(api.changeCatalogSourcePurpose).toHaveBeenCalledWith(40, { version: 5, purpose: 'catalog_source', reason: '核对原件为完整候选，未作我方选择', requestId: expect.any(String), previewToken: 'signed-purpose' }); expect(wrapper.emitted('changed')).toHaveLength(1)
  })
  it('cannot confirm a source with selected, frozen or product-linked history and never silently drops blocking messages', async () => {
    api.catalogSources.mockResolvedValue(page([history])); api.previewCatalogSourcePurpose.mockResolvedValue({ ...history, canChange: false, previewToken: '', messages: ['存在工程已选／产品关联，不能调整用途'] })
    await start(); await prepareHistory(); await click('预览用途调整'); expect(wrapper.text()).toContain('存在工程已选／产品关联'); expect(button('确认整理为供应商资料').attributes('disabled')).toBeDefined()
    await (wrapper.vm as unknown as { confirmPurpose: () => Promise<void> }).confirmPurpose(); expect(api.changeCatalogSourcePurpose).not.toHaveBeenCalled()
  })
  it('pages and scopes historical material without guessing from row state or applying a bulk migration', async () => {
    api.catalogSources.mockResolvedValue(page([history], 21)); await start(); wrapper.getComponent(ElRadioGroup).vm.$emit('update:modelValue', 'historical'); await flushPromises(); wrapper.getComponent(ElPagination).vm.$emit('update:current-page', 2); wrapper.getComponent(ElPagination).vm.$emit('current-change', 2); await flushPromises()
    expect(api.catalogSources).toHaveBeenLastCalledWith({ supplierCode: 'SUP-ONE', purpose: 'historical', page: 2, size: 20 }); expect(api.changeCatalogSourcePurpose).not.toHaveBeenCalled(); expect(api.previewCatalogSourcePurpose).not.toHaveBeenCalled()
  })
  it('blocks direct create and purpose confirmation handlers when engineering write permission is unavailable', async () => {
    await start({ canWrite: false }); expect(button('新建资料并上传')).toBeUndefined(); await (wrapper.vm as unknown as { create: () => Promise<void>; confirmPurpose: () => Promise<void> }).create(); expect(api.createCatalogSource).not.toHaveBeenCalled()
  })
})
