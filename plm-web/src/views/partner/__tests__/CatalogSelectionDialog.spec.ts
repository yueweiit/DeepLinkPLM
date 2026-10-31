import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElCheckboxGroup, ElDialog, ElInputNumber, ElMessageBox, ElRadioGroup, ElTag } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CatalogStyle } from '@/types/partnerCatalog'
const api = vi.hoisted(() => ({ selectionPage: vi.fn(), addCatalogSelection: vi.fn(), previewCatalogSelection: vi.fn() }))
vi.mock('@/api/modules/partnerCatalog', () => ({ addCatalogSelection: api.addCatalogSelection, previewCatalogSelection: api.previewCatalogSelection }))
vi.mock('@/api/modules/partnerSelections', () => ({ selectionPage: api.selectionPage }))
vi.mock('@/api/modules/partner', () => ({ newRequestId: () => crypto.randomUUID() }))
vi.mock('@/components/PhoneModelMultiSelect/index.vue', () => ({ default: { name: 'PhoneModelMultiSelect', props: ['modelValue', 'members', 'allowedIds', 'disabled'], emits: ['update:modelValue','selected'], template: '<div class="real-master-selector-contract"/>' } }))
vi.mock('../PartnerPhoto.vue', () => ({ default: { props: ['id'], template: '<span>本款图片</span>' } }))
import Dialog from '../catalog/CatalogSelectionDialog.vue'
let wrapper: VueWrapper
const identity = { sourceKind: 'quote' as const, recordId: 72, rowId: 'stable-source', rowVersion: 5, recordVersion: 8 }
const source = (overrides: Partial<CatalogStyle> = {}): CatalogStyle => ({ ...identity, sourceCode: 'SQ-72', sourceName: '供应商全量资料', supplierCode: 'SUP-REAL', supplierName: '供方真实名', name: '真实磁吸款', phoneModelIds: [11, 17, 18], phones: [11, 17, 18].map(phoneModelId => ({ phoneModelId, brandId: 1, brandName: 'Apple', modelName: `iPhone ${phoneModelId}`, phoneModelCode: String(phoneModelId).padStart(4, '0') })) as CatalogStyle['phones'], refs: [], fileDetails: [], ...overrides })
const target = { id: 21, code: 'SL-21', name: '真实选品资料', version: 8, canWrite: true }
const result = { selection: { ...target, version: 9 }, rowIds: ['new-row'], addedCount: 1, reusedCount: 0, replayed: false }
beforeEach(() => {
  vi.clearAllMocks(); api.selectionPage.mockResolvedValue({ content: [target], totalElements: 1 }); api.addCatalogSelection.mockResolvedValue(result)
  api.previewCatalogSelection.mockImplementation(async data => ({ canAdd: true, previewToken: 'signed-preview', items: data.items.map((item: Record<string, unknown>) => ({ ...item, name: '真实磁吸款', addedPhoneModelIds: item.phoneModelIds, reusedPhoneModelIds: [], messages: [] })), addedCount: 1, reusedCount: 0, newPhoneCount: 2, messages: [] }))
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = ''; vi.restoreAllMocks() })
async function start(items = [source()], realDialog = false) {
  wrapper = mount(Dialog, { props: { modelValue: false, supplierCode: 'SUP-REAL', supplierName: '供方真实名', items, canWrite: true }, attachTo: document.body, global: { plugins: [ElementPlus], stubs: realDialog ? {} : { ElDialog: { props: ['modelValue','beforeClose','closeOnClickModal'], template: '<section v-if="modelValue"><slot/><slot name="footer"/></section>' } } } })
  await wrapper.setProps({ modelValue: true }); await flushPromises()
}
const button = (text: string) => wrapper.findAll('button').find(button => button.text() === text)!
async function click(text: string) { await button(text).trigger('click'); await flushPromises() }
const modelRow = (index = 0) => wrapper.findAll('.selection-model-row')[index] || new DOMWrapper(document.body.querySelectorAll('.selection-model-row')[index]!)
const rowPicker = (index = 0) => modelRow(index).getComponent({ name: 'PhoneModelMultiSelect' })
const batchPicker = () => wrapper.get('.selection-batch').getComponent({ name: 'PhoneModelMultiSelect' })
async function openRow(index = 0) {
  const adjust = modelRow(index).get('.selection-adjust-button')
  if (adjust.attributes('aria-expanded') !== 'true') { await adjust.trigger('click'); await flushPromises() }
}
async function clickRow(text: string, index = 0) {
  await openRow(index)
  const action = modelRow(index).findAll('button').find(button => button.text() === text)
  expect(action, `row ${index}: ${text}`).toBeDefined(); await action!.trigger('click'); await flushPromises()
}
async function chooseModels(ids = [17, 18], index = 0) {
  await openRow(index); rowPicker(index).vm.$emit('update:modelValue', ids); await flushPromises()
}
async function chooseExistingPhones(members: CatalogStyle['phones'], index = 0) {
  await chooseModels(members.map(phone => phone.phoneModelId), index)
  rowPicker(index).vm.$emit('selected', members); await flushPromises()
}
async function chooseBatchModels(ids = [17, 18], members?: CatalogStyle['phones']) {
  const picker = batchPicker()
  picker.vm.$emit('update:modelValue', ids)
  picker.vm.$emit('selected', members || (picker.props('members') as CatalogStyle['phones']).filter(phone => ids.includes(phone.phoneModelId)))
  await flushPromises()
}
async function applyBatch() { await click(`应用到 ${wrapper.findAll('.selection-model-row').length} 款`) }
async function chooseStyleOnly(value = true, index = 0) {
  await modelRow(index).get('.selection-style-toggle input').setValue(value); await flushPromises()
}

describe('explicit source subset to my selection', () => {
  it('retains displayed quantities after native input, change and blur, intent changes and cancelling the discard prompt', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    await start([source(), source({ rowId: 'second' })]); await chooseModels([17, 18]); await chooseStyleOnly(true, 1)
    const first = modelRow().get<HTMLInputElement>('.selection-row-quantity input')
    first.element.value = '2'; await first.trigger('input'); await first.trigger('change'); await first.trigger('blur'); await flushPromises()
    const second = modelRow(1).get<HTMLInputElement>('.selection-row-quantity input')
    second.element.value = '0'; await second.trigger('input'); await second.trigger('change'); await second.trigger('blur'); await flushPromises()
    const common = wrapper.get<HTMLInputElement>('.selection-quantity-batch input')
    common.element.value = '3'; await common.trigger('input'); await common.trigger('change'); await common.trigger('blur'); await flushPromises()
    await chooseStyleOnly(false, 1); await chooseStyleOnly(true, 1); await click('取消')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined(); expect(wrapper.get('.selection-total').text()).toContain('拟拿样合计 2 件')
    expect(modelRow().get<HTMLInputElement>('.selection-row-quantity input').element.value).toBe('2')
    expect(modelRow(1).get<HTMLInputElement>('.selection-row-quantity input').element.value).toBe('0')
    expect(wrapper.get<HTMLInputElement>('.selection-quantity-batch input').element.value).toBe('3')
    await click('保存选样')
    expect(api.addCatalogSelection.mock.calls[0][0].items).toEqual([{ ...identity, phoneModelIds: [17, 18], sampleQty: 2 }, { ...identity, rowId: 'second', phoneModelIds: [], selectionIntent: 'style', sampleQty: 0 }])
  })
  it('saves only an explicitly chosen style from an unresolved source without guessing phone IDs or losing the original requirement', async () => {
    const row = source({ phoneScopeMode: 'unresolved', phoneModelIds: [], phones: [], modelText: '来源原型号表待关联' })
    await start([row]); expect(button('保存选样').attributes('disabled')).toBeDefined()
    await chooseStyleOnly(); await modelRow().get('.selection-style-text input').setValue('原表机型待定，先保留款式')
    expect(wrapper.get('.selection-total').text()).toContain('已选 1 款')
    expect(modelRow().findComponent({ name: 'PhoneModelMultiSelect' }).exists()).toBe(false)
    expect(modelRow().get('.selection-source-models').text()).toContain('来源原型号表待关联')
    expect(modelRow().find('.selection-issue').exists()).toBe(false)
    expect(button('保存选样').attributes('disabled')).toBeUndefined()
    await click('保存选样')
    expect(api.previewCatalogSelection.mock.calls[0][0].items).toEqual([{ ...identity, phoneModelIds: [], selectionIntent: 'style', selectionModelText: '原表机型待定，先保留款式' }])
    expect(api.addCatalogSelection.mock.calls[0][0].items).toEqual(api.previewCatalogSelection.mock.calls[0][0].items)
    expect(row.phoneModelIds).toEqual([]); expect(row.modelText).toBe('来源原型号表待关联')
  })
  it.each([undefined, 0, 2])('keeps the original per-style quantity %s for a style-only choice alongside explicitly selected phones', async quantity => {
    await start([source(), source({ rowId: 'second', name: '明确型号款' })])
    await chooseStyleOnly(); await chooseModels([17, 18], 1)
    if (quantity !== undefined) { modelRow().get('.selection-row-quantity').getComponent(ElInputNumber).vm.$emit('update:modelValue', quantity); await flushPromises() }
    expect(wrapper.get('.selection-total').text()).toContain('已选 2 款')
    await click('保存选样')
    expect(api.addCatalogSelection.mock.calls[0][0].items).toEqual([
      { ...identity, phoneModelIds: [], selectionIntent: 'style', ...(quantity !== undefined ? { sampleQty: quantity } : {}) },
      { ...identity, rowId: 'second', phoneModelIds: [17, 18] }
    ])
  })
  it('restores the same temporary model and colour choices when style-only mode is turned off', async () => {
    await start([source({ pairings: [{ phoneModelId: 18, colorId: 4, state: 'available', colorSnapshot: { id: 4, name: '原黑色' } }] })])
    await chooseModels([18]); modelRow().getComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['18:4']); await flushPromises()
    await chooseStyleOnly(); await modelRow().get('.selection-style-text input').setValue('暂时保留的选品原文')
    expect((wrapper.vm as unknown as { selectedItems: unknown[] }).selectedItems).toEqual([{ ...identity, phoneModelIds: [], selectionIntent: 'style', selectionModelText: '暂时保留的选品原文' }])
    expect(modelRow().get('header small').text()).toContain('本次选 0 个')
    await chooseStyleOnly(false)
    expect(rowPicker().props('modelValue')).toEqual([18]); expect(modelRow().getComponent(ElCheckboxGroup).props('modelValue')).toEqual(['18:4'])
    await click('保存选样')
    expect(api.addCatalogSelection.mock.calls[0][0].items).toEqual([{ ...identity, phoneModelIds: [18], selectedPairs: [{ phoneModelId: 18, colorId: 4 }] }])
  })
  it('keeps a style-only exception through batch application and leaves it only on explicit restoration or clear', async () => {
    await start([source(), source({ rowId: 'second' })]); await chooseStyleOnly()
    await chooseBatchModels([17, 18]); await applyBatch()
    expect(modelRow().get('.selection-style-toggle input').element).toHaveProperty('checked', true)
    expect(rowPicker(1).props('modelValue')).toEqual([17, 18]); expect(wrapper.get('.selection-total').text()).toContain('已选 2 款')
    await clickRow('恢复统一选择')
    expect(modelRow().get('.selection-style-toggle input').element).toHaveProperty('checked', false); expect(rowPicker().props('modelValue')).toEqual([17, 18])
    await chooseStyleOnly(); await click('清空')
    expect(modelRow().get('.selection-style-toggle input').element).toHaveProperty('checked', false)
    expect(rowPicker().props('modelValue')).toEqual([]); expect(wrapper.get('.selection-total').text()).toContain('已选 0 款')
    expect(button('保存选样').attributes('disabled')).toBeDefined(); expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })
  it('treats a style-only choice and its original text as unsaved input when cancelling and resets it on a new opening', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel').mockResolvedValueOnce('confirm' as never)
    await start(); await chooseStyleOnly(); await modelRow().get('.selection-style-text input').setValue('先选款式，型号后定')
    await click('取消'); expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(modelRow().get('.selection-style-text input').element).toHaveProperty('value', '先选款式，型号后定')
    expect(modelRow().get('.selection-style-toggle input').element).toHaveProperty('checked', true)
    await click('取消'); expect(confirm).toHaveBeenCalledTimes(2); expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    await wrapper.setProps({ modelValue: false }); await wrapper.setProps({ modelValue: true }); await flushPromises()
    expect(modelRow().get('.selection-style-toggle input').element).toHaveProperty('checked', false); expect(modelRow().find('.selection-style-text').exists()).toBe(false)
    expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })
  it('reconciles a style-only save with the exact original request and locks its intent while the result is unknown', async () => {
    api.addCatalogSelection.mockRejectedValueOnce({ code: 'ECONNABORTED' }).mockResolvedValue({ ...result, replayed: true })
    await start(); await chooseStyleOnly(); await modelRow().get('.selection-style-text input').setValue('原机型要求待补')
    await click('保存选样'); const original = JSON.parse(JSON.stringify(api.addCatalogSelection.mock.calls[0][0]))
    expect(modelRow().get('.selection-style-toggle input').attributes('disabled')).toBeDefined()
    expect(modelRow().get('.selection-style-text input').attributes('disabled')).toBeDefined()
    await (wrapper.vm as unknown as { changeStyleOnly: (row: CatalogStyle, value: boolean) => void }).changeStyleOnly(source(), false)
    expect(modelRow().get('.selection-style-toggle input').element).toHaveProperty('checked', true)
    await click('核对保存结果')
    expect(api.previewCatalogSelection).toHaveBeenCalledTimes(1); expect(api.addCatalogSelection.mock.calls[1][0]).toEqual(original)
    expect(wrapper.emitted('added')).toHaveLength(1)
  })
  it('shows the recorded supplier progress for chosen models without changing availability, guessing stock or widening the source scope',async()=>{await start([source({supplierModelEntries:[{supplierIndex:1,rawBrandGroup:'Apple',rawModelText:'iPhone 17',rawStatus:'开模中',rawNote:'按原表等待',phoneModelIds:[17]},{supplierIndex:2,rawBrandGroup:'Apple',rawModelText:'iPhone 18',rawStatus:'试模',rawNote:'原备注',phoneModelIds:[18]}]})]);expect(wrapper.find('.selection-supplier-progress').exists()).toBe(false);await chooseModels([17,18]);expect(wrapper.get('.selection-supplier-progress').text()).toContain('iPhone 17');expect(wrapper.get('.selection-supplier-progress').text()).toContain('开模中 · 按原表等待');expect(wrapper.get('.selection-supplier-progress').text()).toContain('试模 · 原备注');expect(rowPicker().props('allowedIds')).toEqual([11,17,18]);expect(button('保存选样').attributes('disabled')).toBeUndefined();await flushPromises();await click('保存选样');expect(api.previewCatalogSelection.mock.calls[0][0].items[0].phoneModelIds).toEqual([17,18]);expect(api.addCatalogSelection).toHaveBeenCalledTimes(1)})
  it('blocks mask dismissal and keeps selected models when the real close icon confirmation is cancelled',async()=>{
    const confirm=vi.spyOn(ElMessageBox,'confirm').mockRejectedValue('cancel');await start([source()],true);await chooseModels([18])
    expect(wrapper.getComponent(ElDialog).props('closeOnClickModal')).toBe(false)
    const icon=document.querySelector<HTMLButtonElement>('.catalog-selection-dialog-shell .el-dialog__headerbtn');expect(icon).not.toBeNull();icon!.click();await flushPromises()
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('还未保存'), '保留本次选品',expect.objectContaining({confirmButtonText:'放弃并关闭',cancelButtonText:'继续选品'}))
    expect(wrapper.emitted('update:modelValue')).toBeUndefined();expect(rowPicker().props('modelValue')).toEqual([18]);expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })
  it('keeps chosen models and planned quantities when discarding is cancelled, then closes only after explicit discard',async()=>{const confirm=vi.spyOn(ElMessageBox,'confirm').mockRejectedValueOnce('cancel').mockResolvedValueOnce('confirm' as never);await start();await chooseModels();modelRow().get('.selection-row-quantity').getComponent(ElInputNumber).vm.$emit('update:modelValue',2);await flushPromises();await click('取消');expect(wrapper.emitted('update:modelValue')).toBeUndefined();expect(rowPicker().props('modelValue')).toEqual([17,18]);expect(modelRow().getComponent(ElInputNumber).props('modelValue')).toBe(2);await click('取消');expect(confirm).toHaveBeenCalledTimes(2);expect(wrapper.emitted('update:modelValue')).toEqual([[false]]);expect(api.addCatalogSelection).not.toHaveBeenCalled()})
  it('has no destination step or query and directly saves after choosing supplied models without a second discard prompt',async()=>{const confirm=vi.spyOn(ElMessageBox,'confirm');await start();expect(wrapper.find('.selection-steps').exists()).toBe(false);expect(wrapper.find('.selection-targets').exists()).toBe(false);expect(wrapper.find('[aria-label="选品资料名称"]').exists()).toBe(false);expect(wrapper.findComponent(ElRadioGroup).exists()).toBe(false);expect(api.selectionPage).not.toHaveBeenCalled();await chooseModels();expect(button('保存选样').attributes('disabled')).toBeUndefined();await click('保存选样');expect(wrapper.emitted('added')).toHaveLength(1);expect(confirm).not.toHaveBeenCalled();expect(api.selectionPage).not.toHaveBeenCalled()})
  it('starts unselected and reuses the shared selector restricted to this source without matching phones again', async () => {
    await start(); const picker = rowPicker()
    expect(picker.props()).toMatchObject({ modelValue: [], allowedIds: [11, 17, 18], members: source().phones })
    expect(batchPicker().props('modelValue')).toEqual([]); expect(batchPicker().attributes('aria-label')).toBe('批量选择系列／型号')
    expect(button('应用到 1 款').attributes('disabled')).toBeDefined()
    expect(button('保存选样').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.selection-target').exists()).toBe(false)
    expect(api.previewCatalogSelection).not.toHaveBeenCalled(); expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })
  it.each([{scope:undefined,text:''},{scope:'supplier' as const,text:''},{scope:'unresolved' as const,text:''},{scope:undefined,text:'供方X系列，暂未匹配'},{scope:'supplier' as const,text:'供方X系列，暂未匹配'},{scope:'unresolved' as const,text:'原件图片型号表待提取'}])('does not open the phone library for an empty or unmatched supplier scope: $scope / $text',async({scope,text})=>{
    await start([source({phoneScopeMode:scope,phoneModelIds:[],phones:[],modelText:text})])
    expect(modelRow().findComponent({name:'PhoneModelMultiSelect'}).exists()).toBe(false);expect(wrapper.text()).toContain('本款型号资料尚未提取或关联，请先核对来源型号表');expect(wrapper.text()).not.toContain('供应商未注明型号');expect(wrapper.text()).not.toContain('可从现有资料选择');expect(button('保存选样').attributes('disabled')).toBeDefined();expect(api.previewCatalogSelection).not.toHaveBeenCalled()
    expect(wrapper.get('.selection-model-row header small').text()).toBe('型号资料待关联 · 本次选 0 个');expect(wrapper.text()).not.toContain('本款可选 0 个型号')
  })
  it('saves an explicit unspecified source using real selected members and no client scope authority',async()=>{const picked={...source().phones[0],phoneModelId:79,modelName:'本地已选型号名'};await start([source({phoneScopeMode:'unspecified',phoneModelIds:[],phones:[],modelText:''})]);expect(rowPicker().props('allowedIds')).toBeUndefined();await chooseExistingPhones([picked]);await flushPromises();expect(button('预览加入结果')).toBeUndefined();await click('保存选样');expect(api.addCatalogSelection.mock.calls[0][0].items).toEqual([{...identity,phoneModelIds:[79]}]);expect(api.addCatalogSelection.mock.calls[0][0]).not.toHaveProperty('phoneScopeMode');expect(wrapper.find('.selection-add-preview').exists()).toBe(false)})
  it('applies explicitly picked real members to unspecified rows without expanding a bounded row or claiming confirmed supply', async () => {
    const member = { ...source().phones[0], phoneModelId: 79, modelName: '现有机型79' }
    await start([source({ phoneScopeMode: 'unspecified', phoneModelIds: [], phones: [] }), source({ rowId: 'second', phoneScopeMode: 'unspecified', phoneModelIds: [], phones: [] }), source({ rowId: 'third', phoneModelIds: [18], phones: [source().phones[2]] })])
    expect(batchPicker().props('allowedIds')).toBeUndefined()
    await chooseBatchModels([79], [member]); await applyBatch()
    expect(rowPicker().props('modelValue')).toEqual([79]); expect(rowPicker(1).props('modelValue')).toEqual([79]); expect(rowPicker(2).props('modelValue')).toEqual([])
    expect(modelRow().get('.selection-row-notice').text()).toContain('不代表已确认可供')
    expect(modelRow(2).text()).toContain('未选入：现有机型79'); expect(button('保存选样').attributes('disabled')).toBeDefined()
    await chooseModels([18], 2); await flushPromises(); await click('保存选样')
    expect(wrapper.find('.selection-add-preview').exists()).toBe(false)
    expect(api.previewCatalogSelection.mock.calls[0][0].items).toEqual([{ ...identity, phoneModelIds: [79] }, { ...identity, rowId: 'second', phoneModelIds: [79] }, { ...identity, rowId: 'third', phoneModelIds: [18] }])
    expect(api.selectionPage).not.toHaveBeenCalled(); expect(api.addCatalogSelection).toHaveBeenCalledTimes(1)
  })

  it('preserves a manually chosen unspecified exception until the engineer explicitly restores the latest common choice', async () => {
    await start([source(), source({ rowId: 'second', phoneScopeMode: 'unspecified', phoneModelIds: [], phones: [] })])
    await chooseBatchModels([18]); await applyBatch()
    await chooseExistingPhones([{ ...source().phones[0], phoneModelId: 79, modelName: '现有例外型号' }], 1)
    await chooseBatchModels([17]); await applyBatch()
    expect(rowPicker().props('modelValue')).toEqual([17]); expect(rowPicker(1).props('modelValue')).toEqual([79])
    expect(modelRow(1).text()).toContain('已单独调整'); expect(wrapper.get('.selection-batch-message').text()).toContain('保留 1 款单独调整')
    await clickRow('恢复统一选择', 1)
    expect(rowPicker(1).props('modelValue')).toEqual([17]); expect(modelRow(1).text()).not.toContain('已单独调整')
    expect(modelRow(1).get('.selection-row-notice').text()).toContain('不代表已确认可供'); expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })

  it('blocks a changed source scope before the one-click save without broadening old phone IDs',async()=>{const picked={...source().phones[0],phoneModelId:79,modelName:'原先选择79'};await start([source({phoneScopeMode:'unspecified',phoneModelIds:[],phones:[],modelText:''})]);await chooseExistingPhones([picked]);await flushPromises();await wrapper.setProps({items:[source({phoneScopeMode:'supplier',phoneModelIds:[18],modelText:'只供18',rowVersion:6})]});expect(button('保存选样').attributes('disabled')).toBeDefined();await flushPromises();expect(rowPicker().props('allowedIds')).toEqual([18]);expect(wrapper.text()).toContain('不在当前允许范围');expect(api.addCatalogSelection).not.toHaveBeenCalled()})
  it.each([undefined,'unresolved' as const])('ignores a late unspecified preview after the source switches back to a restricted or missing mode: %s',async(mode)=>{
    let resolve!:(value:unknown)=>void;api.previewCatalogSelection.mockImplementation(()=>new Promise(done=>{resolve=done}))
    const picked={...source().phones[0],phoneModelId:79,modelName:'已有型号79'}
    await start([source({phoneScopeMode:'unspecified',phoneModelIds:[],phones:[]})]);await chooseExistingPhones([picked]);await flushPromises();await click('保存选样')
    await wrapper.setProps({items:[source({phoneScopeMode:mode,phoneModelIds:[],phones:[],modelText:'无法匹配的范围',rowVersion:6})]});resolve({canAdd:true,previewToken:'stale',items:[],addedCount:1,reusedCount:0,newPhoneCount:1,messages:[]});await flushPromises()
    expect(wrapper.find('.selection-add-preview').exists()).toBe(false);expect(button('保存选样').attributes('disabled')).toBeDefined();expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })
  it('shows the source scope and explicitly selects only supplied iPhones, excluding unavailable phones and out-of-scope master members',async()=>{
    const phones=[11,12,18,19].map(id=>({...source().phones[0],phoneModelId:id,modelName:`iPhone ${id}`}))
    phones.push({...source().phones[0],phoneModelId:27,modelName:'iPad Pro'})
    await start([source({modelText:'iPhone11至18（12已不做）',phoneModelIds:[11,12,18,27],unavailablePhoneModelIds:[12],phones})])
    const picker=()=>rowPicker()
    expect(wrapper.get('.selection-source-models').text()).toBe('来源型号：iPhone11至18（12已不做）')
    expect(picker().props('modelValue')).toEqual([]);expect(picker().props('allowedIds')).toEqual([11,18,27])
    expect(picker().attributes('placeholder')).toContain('选择系列／型号')
    await clickRow('选择iPhone系列（2）');expect(picker().props('modelValue')).toEqual([11,18])
    await flushPromises();await click('保存选样')
    expect(api.addCatalogSelection.mock.calls[0][0].items).toEqual([{...identity,phoneModelIds:[11,18]}])
    expect(phones.map(phone=>phone.phoneModelId)).toEqual([11,12,18,19,27])
  })
  it('selects the actual Samsung S family without A phones or conflicting aliases, preserving other explicit choices',async()=>{
    const samsung=(id:number,modelName:string,aliases:string[]=[])=>({...source().phones[0],phoneModelId:id,brandId:2,brandName:'Samsung',modelName,aliases})
    await start([source({phoneModelIds:[201,202,203,204,205],unavailablePhoneModelIds:[205],phones:[samsung(201,'Galaxy S22'),samsung(202,'Samsung Galaxy S23'),samsung(203,'Galaxy A24'),samsung(204,'Galaxy S24',['Galaxy A14']),samsung(205,'Galaxy S12')]})])
    await chooseModels([203]);await clickRow('选择Samsung Galaxy S系列（2）')
    expect(rowPicker().props('modelValue')).toEqual([203,201,202])
    await clickRow('取消Samsung Galaxy S系列（2）');expect(rowPicker().props('modelValue')).toEqual([203])
    expect(api.previewCatalogSelection).not.toHaveBeenCalled();expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })
  it('keeps unfamiliar series in the existing selector and does not infer a family or offer any phones when the source is explicitly unavailable',async()=>{
    await start([source({phoneModelIds:[88],phones:[{...source().phones[0],phoneModelId:88,brandName:'Other',modelName:'S款',seriesId:8,seriesName:'原厂系列'}]})])
    expect(wrapper.find('.selection-series-shortcuts').exists()).toBe(false);expect(rowPicker().props('allowedIds')).toEqual([88])
    await wrapper.setProps({modelValue:false,items:[source({unavailablePhoneModelIds:[11,17,18]})]});await wrapper.setProps({modelValue:true});await flushPromises()
    expect(wrapper.get('.selection-model-row header small').text()).toBe('当前范围暂无可选型号 · 本次选 0 个');expect(wrapper.text()).not.toContain('型号资料待关联')
    expect(modelRow().findComponent({name:'PhoneModelMultiSelect'}).exists()).toBe(false);expect(wrapper.text()).toContain('暂无可选型号');expect(button('保存选样').attributes('disabled')).toBeDefined()
  })
  it('selects all explicit source ranges without unavailable or disabled members, guessed unspecified phones or automatic colour pairs', async () => {
    const actual = source()
    await start([source({ unavailablePhoneModelIds: [11], phones: actual.phones.map(phone => phone.phoneModelId === 18 ? { ...phone, status: 'disabled' } : phone), pairings: [{ phoneModelId: 17, colorId: 4, state: 'available', colorSnapshot: { id: 4, name: '实际供方黑' } }] }), source({ rowId: 'unknown', phoneScopeMode: 'unspecified', phoneModelIds: [], phones: [] }), source({ rowId: 'unresolved', phoneScopeMode: 'unresolved', phoneModelIds: [18], phones: [actual.phones[2]] })])
    await click('各款可选型号全选')
    expect(rowPicker().props('modelValue')).toEqual([17]); expect(rowPicker(1).props('modelValue')).toEqual([]); expect(rowPicker(2).props('modelValue')).toEqual([])
    expect(rowPicker(2).props('disabled')).toBe(true); expect(modelRow(2).text()).toContain('请先核对来源型号表')
    expect(modelRow(1).text()).toContain('不代表已确认可供'); expect(button('保存选样').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.selection-total').text()).toContain('已选 1 款'); expect(wrapper.getComponent(ElCheckboxGroup).props('modelValue')).toEqual([])
    await clickRow('本次不选', 1); await clickRow('本次不选', 2)
    await flushPromises(); await click('保存选样')
    expect(api.addCatalogSelection.mock.calls[0][0].items).toEqual([{ ...identity, phoneModelIds: [17] }]); expect(actual.phoneModelIds).toEqual([11, 17, 18])
  })
  it('shows rejected batch IDs on each affected style and never adopts unavailable or disabled models', async () => {
    const phones = source().phones.map(phone => phone.phoneModelId === 18 ? { ...phone, status: 'disabled' as const } : phone)
    await start([source({ phoneModelIds: [11, 17], unavailablePhoneModelIds: [17], phones }), source({ rowId: 'disabled', phoneModelIds: [17, 18], phones })])
    await chooseBatchModels([11, 17, 18]); await applyBatch()
    expect(rowPicker().props('modelValue')).toEqual([11]); expect(rowPicker(1).props('modelValue')).toEqual([17])
    expect(modelRow().text()).toContain('未选入：iPhone 17、iPhone 18'); expect(modelRow(1).text()).toContain('未选入：iPhone 11、iPhone 18')
    await flushPromises(); await click('保存选样')
    expect(api.previewCatalogSelection.mock.calls[0][0].items).toEqual([{ ...identity, phoneModelIds: [11] }, { ...identity, rowId: 'disabled', phoneModelIds: [17] }]); expect(api.addCatalogSelection).toHaveBeenCalledTimes(1)
  })
  it('uses actual family members for an explicit batch shortcut into unspecified rows without inferring supply', async () => {
    await start([source(), source({ rowId: 'unspecified', phoneScopeMode: 'unspecified', phoneModelIds: [], phones: [] })])
    const family = wrapper.get('.selection-batch-shortcuts').findAll('button').find(button => button.text() === '选择iPhone系列（3）')!
    await family.trigger('click'); await flushPromises()
    expect(rowPicker().props('modelValue')).toEqual([11, 17, 18]); expect(rowPicker(1).props('modelValue')).toEqual([11, 17, 18])
    expect(modelRow(1).text()).toContain('iPhone 11'); expect(modelRow(1).text()).toContain('不代表已确认可供'); expect(button('保存选样').attributes('disabled')).toBeUndefined()
    expect(api.previewCatalogSelection).not.toHaveBeenCalled(); expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })
  it('does not revive an old select-all after clear, a single-style edit and restoring the current common choice', async () => {
    await start(); await click('各款可选型号全选'); expect(rowPicker().props('modelValue')).toEqual([11, 17, 18])
    await click('清空'); expect(batchPicker().props('modelValue')).toEqual([]); expect(rowPicker().props('modelValue')).toEqual([])
    await chooseModels([18]); await clickRow('恢复统一选择')
    expect(rowPicker().props('modelValue')).toEqual([]); expect(modelRow().classes()).not.toContain('is-excluded')
    expect(modelRow().text()).toContain('未选型号'); expect(wrapper.get('.selection-total').text()).toContain('已选 0 款')
    expect(button('保存选样').attributes('disabled')).toBeDefined(); expect(api.previewCatalogSelection).not.toHaveBeenCalled()
  })
  it('does not precreate a missing supplier directory and lets the server create it only on the same direct save path',async()=>{api.previewCatalogSelection.mockResolvedValue({canAdd:true,previewToken:'create-token',targetSelectionId:0,targetVersion:0,targetName:'供方真实名样品选择',items:[],messages:[]});await start();expect(api.selectionPage).not.toHaveBeenCalled();expect(api.previewCatalogSelection).not.toHaveBeenCalled();expect(api.addCatalogSelection).not.toHaveBeenCalled();await chooseModels([18]);await click('保存选样');expect(api.addCatalogSelection).toHaveBeenCalledWith({supplierCode:'SUP-REAL',targetMode:'supplier_directory',requestId:expect.any(String),previewToken:'create-token',items:[{...identity,phoneModelIds:[18]}]});expect(api.addCatalogSelection.mock.calls[0][0]).not.toHaveProperty('selectionName')})
  it('keeps a server-resolved destination out of the original auto-target payload',async()=>{api.previewCatalogSelection.mockResolvedValue({canAdd:true,previewToken:'existing-token',targetSelectionId:99,targetVersion:12,targetName:'现有供方样品选择',items:[],messages:[]});await start();await chooseModels([18]);await click('保存选样');const data=api.addCatalogSelection.mock.calls[0][0];expect(data.targetMode).toBe('supplier_directory');expect(data).not.toHaveProperty('targetSelectionId');expect(data).not.toHaveProperty('targetVersion');expect(api.selectionPage).not.toHaveBeenCalled()})
  it('passes exact workbench context to server validation without exposing a destination selector',async()=>{await start();await wrapper.setProps({targetSelectionId:77,targetVersion:8});await chooseModels([18]);await click('保存选样');expect(api.previewCatalogSelection).toHaveBeenCalledWith(expect.objectContaining({targetMode:'supplier_directory',targetSelectionId:77,targetVersion:8}));expect(api.addCatalogSelection).toHaveBeenCalledWith(expect.objectContaining({targetSelectionId:77,targetVersion:8,previewToken:'signed-preview'}));expect(wrapper.find('.selection-target').exists()).toBe(false);expect(api.selectionPage).not.toHaveBeenCalled()})
  it('cancels without creating a sheet, product or sample', async () => {
    await start(); await click('取消'); expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    expect(api.addCatalogSelection).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('不会提前生成产品或样品批次')
  })
  it('one save internally validates and sends explicit model IDs, source versions and target lock without an extra confirmation',async()=>{await start();await chooseModels();expect(button('保存选样').attributes('disabled')).toBeUndefined();expect(button('预览加入结果')).toBeUndefined();await click('保存选样');expect(api.previewCatalogSelection).toHaveBeenCalledWith({supplierCode:'SUP-REAL',items:[{...identity,phoneModelIds:[17,18]}],targetMode:'supplier_directory'});expect(api.addCatalogSelection).toHaveBeenCalledWith({supplierCode:'SUP-REAL',requestId:expect.any(String),previewToken:'signed-preview',items:[{...identity,phoneModelIds:[17,18]}],targetMode:'supplier_directory'});expect(api.previewCatalogSelection.mock.invocationCallOrder[0]).toBeLessThan(api.addCatalogSelection.mock.invocationCallOrder[0]);expect(wrapper.emitted('added')).toEqual([[result]]);expect(api.addCatalogSelection.mock.calls[0][0].items[0]).not.toHaveProperty('refs')})
  it('sends an ID-only legacy workbench hint without inventing a target version or name',async()=>{await start();await wrapper.setProps({targetSelectionId:77});await chooseModels();await click('保存选样');const data=api.addCatalogSelection.mock.calls[0][0];expect(data).toMatchObject({targetMode:'supplier_directory',targetSelectionId:77});expect(data).not.toHaveProperty('targetVersion');expect(data).not.toHaveProperty('selectionName')})
  it('keeps the save screen concise and never renders a successful validation list',async()=>{await start();await chooseModels();expect(wrapper.find('.selection-add-preview').exists()).toBe(false);expect(wrapper.get('.selection-total').text()).toContain('已选 1 款');expect(wrapper.text()).not.toContain('款式型号组合');await click('保存选样');expect(wrapper.find('.selection-add-preview').exists()).toBe(false);expect(api.addCatalogSelection).toHaveBeenCalledTimes(1)})
  it('applies a per-style total independently of model changes and preserves explicit quantity exceptions',async()=>{await start([source(),source({rowId:'second',name:'另一款'})]);await chooseBatchModels([17,18]);await applyBatch();await chooseModels([18],0);modelRow(1).get('.selection-row-quantity').getComponent(ElInputNumber).vm.$emit('update:modelValue',3);wrapper.get('.selection-quantity-batch').getComponent(ElInputNumber).vm.$emit('update:modelValue',2);await flushPromises();await click('应用每款数量');expect(modelRow(0).get('.selection-row-quantity').getComponent(ElInputNumber).props('modelValue')).toBe(2);expect(modelRow(1).get('.selection-row-quantity').getComponent(ElInputNumber).props('modelValue')).toBe(3);expect(wrapper.get('.selection-total').text()).toContain('拟拿样合计 5 件');await flushPromises();await click('保存选样');expect(api.addCatalogSelection.mock.calls[0][0].items).toEqual([{...identity,phoneModelIds:[18],sampleQty:2},{...identity,rowId:'second',phoneModelIds:[17,18],sampleQty:3}])})
  it('leaves unspecified planned quantity absent rather than sending zero or a phone-count multiplier',async()=>{await start();await chooseModels();expect(wrapper.get('.selection-total').text()).toContain('1 款未填数量');await click('保存选样');expect(api.addCatalogSelection.mock.calls[0][0].items[0]).not.toHaveProperty('sampleQty');expect(api.addCatalogSelection.mock.calls[0][0].items[0].phoneModelIds).toEqual([17,18])})
  it('shows a source-version validation failure inline and never performs the add',async()=>{api.previewCatalogSelection.mockResolvedValue({canAdd:false,previewToken:'',items:[{...identity,name:'真实磁吸款',phoneModelIds:[17,18],messages:['来源版本已改变，请重新核对']}],messages:[]});await start();await chooseModels();await click('保存选样');expect(wrapper.get('.selection-errors').text()).toContain('真实磁吸款：来源版本已改变');expect(api.addCatalogSelection).not.toHaveBeenCalled();expect(button('保存选样').attributes('disabled')).toBeUndefined()})
  it('handles failed internal validation without missing-array errors or losing the selected phones',async()=>{api.previewCatalogSelection.mockResolvedValue({canAdd:false,previewToken:'',items:[{...identity,name:'真实磁吸款',messages:['目录来源版本已变化，请刷新后重试']}],messages:[]});await start();await chooseModels();await click('保存选样');expect(wrapper.get('.selection-errors').text()).toContain('目录来源版本已变化');expect(rowPicker().props('modelValue')).toEqual([17,18]);expect(api.addCatalogSelection).not.toHaveBeenCalled()})
  it('retains common choices after a known failed save and validates a changed model selection again',async()=>{api.addCatalogSelection.mockRejectedValueOnce(new Error('连接失败'));await start();await chooseBatchModels();await applyBatch();await click('保存选样');expect(batchPicker().props('modelValue')).toEqual([17,18]);expect(rowPicker().props('modelValue')).toEqual([17,18]);await chooseBatchModels([18]);await applyBatch();await click('保存选样');expect(api.previewCatalogSelection.mock.calls[1][0].items).toEqual([{...identity,phoneModelIds:[18]}]);expect(api.addCatalogSelection).toHaveBeenCalledTimes(2)})
  it('rejects manually injected out-of-source IDs and unknown source models rather than silently selecting all', async () => {
    await start(); await chooseModels([999]); expect(button('保存选样').attributes('disabled')).toBeDefined()
    await (wrapper.vm as unknown as { submit: () => Promise<void> }).submit(); expect(api.previewCatalogSelection).not.toHaveBeenCalled()
    await wrapper.setProps({ modelValue: false, items: [source({ phoneModelIds: [], phones: [], modelText: '原件不明范围' })] }); await wrapper.setProps({ modelValue: true }); await flushPromises()
    expect(wrapper.text()).toContain('本款型号资料尚未提取或关联，请先核对来源型号表'); expect(modelRow().findComponent({name:'PhoneModelMultiSelect'}).exists()).toBe(false)
  })
  it('applies the common real IDs by each style intersection and keeps incompatible requested models visible in that row', async () => {
    await start([source(), source({ rowId: 'second', name: '仅供18款', phoneModelIds: [18] })])
    expect(batchPicker().attributes('aria-label')).toBe('批量选择系列／型号'); expect(wrapper.find('.selection-batch-picker').exists()).toBe(true)
    await chooseBatchModels([17, 18]); expect(rowPicker().props('modelValue')).toEqual([])
    await applyBatch()
    expect(rowPicker().props('modelValue')).toEqual([17, 18]); expect(rowPicker(1).props('modelValue')).toEqual([18])
    expect(modelRow(1).text()).toContain('未选入：iPhone 17'); expect(modelRow(1).text()).toContain('不在本款可选范围或已停用')
    expect(wrapper.get('.selection-total').text()).toContain('已选 2 款')
    await flushPromises(); await click('保存选样')
    expect(wrapper.find('.selection-add-preview').exists()).toBe(false)
    expect(api.addCatalogSelection.mock.calls[0][0]).toEqual({ supplierCode: 'SUP-REAL', requestId: expect.any(String), previewToken: 'signed-preview', targetMode: 'supplier_directory', items: [{ ...identity, phoneModelIds: [17, 18] }, { ...identity, rowId: 'second', phoneModelIds: [18] }] })
  })

  it('preserves adjusted and explicitly excluded styles across common applications, including removal of the last chip', async () => {
    await start([source(), source({ rowId: 'second' }), source({ rowId: 'third' })])
    await chooseModels([18], 1); await clickRow('本次不选', 2)
    await chooseBatchModels([17, 18]); await applyBatch()
    expect(rowPicker().props('modelValue')).toEqual([17, 18]); expect(rowPicker(1).props('modelValue')).toEqual([18]); expect(rowPicker(2).props('modelValue')).toEqual([])
    expect(modelRow(2).classes()).toContain('is-excluded')
    modelRow(1).getComponent(ElTag).vm.$emit('close'); await flushPromises()
    await chooseBatchModels([11]); await applyBatch()
    expect(rowPicker().props('modelValue')).toEqual([11]); expect(rowPicker(1).props('modelValue')).toEqual([]); expect(rowPicker(2).props('modelValue')).toEqual([])
    expect(modelRow(1).classes()).toContain('is-excluded'); expect(wrapper.get('.selection-total').text()).toContain('已选 1 款')
    await clickRow('恢复统一选择', 1); expect(rowPicker(1).props('modelValue')).toEqual([11])
    await chooseModels([18], 1); expect(rowPicker().props('modelValue')).toEqual([11])
    await clickRow('恢复统一选择', 2); expect(rowPicker(2).props('modelValue')).toEqual([11])
    expect(modelRow(2).classes()).not.toContain('is-excluded'); expect(rowPicker(1).props('modelValue')).toEqual([18]); expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })

  it('allows a model-only draft despite recorded colours and rejects direct injection of a source-unavailable phone',async()=>{
    await start([source({unavailablePhoneModelIds:[11],color:'供方黑',pairings:[{phoneModelId:18,colorId:4,state:'available',colorSnapshot:{id:4,name:'供方黑'}}]})])
    await chooseModels([11]);expect(button('保存选样').attributes('disabled')).toBeDefined();await (wrapper.vm as unknown as {submit:()=>Promise<void>}).submit();expect(api.previewCatalogSelection).not.toHaveBeenCalled()
    await chooseModels([18]);await flushPromises();await click('保存选样')
    expect(api.addCatalogSelection.mock.calls[0][0].items).toEqual([{...identity,phoneModelIds:[18]}])
  })
  it('only offers named real supply pairs for chosen phones and never forms a color cross product', async () => {
    await start([source({ color: '黑／蓝', pairings: [{ phoneModelId: 18, colorId: 4, state: 'available', colorSnapshot: { id: 4, name: '供方原黑' } }, { phoneModelId: 17, colorId: 5, state: 'unavailable', colorSnapshot: { id: 5, name: '明确不供蓝' } }, { phoneModelId: 17, colorId: 6, state: 'available' }] })]); await chooseModels([18])
    expect(wrapper.text()).toContain('供方原黑'); expect(wrapper.text()).not.toContain('明确不供蓝'); expect(wrapper.text()).not.toContain('颜色 #')
    wrapper.getComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['18:4']); await flushPromises(); await flushPromises(); await click('保存选样')
    expect(api.previewCatalogSelection.mock.calls[0][0].items[0]).toMatchObject({ phoneModelIds: [18], selectedPairs: [{ phoneModelId: 18, colorId: 4 }] })
  })
  it('preserves an individual colour choice during batch changes and drops it only on explicit restoration without reviving it later', async () => {
    await start([source({ pairings: [{ phoneModelId: 17, colorId: 4, state: 'available', colorSnapshot: { id: 4, name: '真实供方黑' } }] }), source({ rowId: 'second' })])
    await chooseBatchModels([17]); await applyBatch(); await openRow()
    modelRow().getComponent(ElCheckboxGroup).vm.$emit('update:modelValue', ['17:4']); await flushPromises()
    await chooseBatchModels([18]); await applyBatch()
    expect(rowPicker().props('modelValue')).toEqual([17]); expect(rowPicker(1).props('modelValue')).toEqual([18]); expect(modelRow().text()).toContain('已单独调整')
    expect(modelRow().getComponent(ElCheckboxGroup).props('modelValue')).toEqual(['17:4'])
    await flushPromises(); await flushPromises()
    expect((wrapper.vm as unknown as {selectedItems:unknown[]}).selectedItems).toEqual([{ ...identity, phoneModelIds: [17], selectedPairs: [{ phoneModelId: 17, colorId: 4 }] }, { ...identity, rowId: 'second', phoneModelIds: [18] }])
    await flushPromises(); await clickRow('恢复统一选择')
    expect(rowPicker().props('modelValue')).toEqual([18]); expect(modelRow().findComponent(ElCheckboxGroup).exists()).toBe(false)
    await chooseBatchModels([17]); await applyBatch()
    expect(modelRow().getComponent(ElCheckboxGroup).props('modelValue')).toEqual([])
    await click('保存选样')
    expect(api.addCatalogSelection.mock.calls[0][0].items).toEqual([{ ...identity, phoneModelIds: [17] }, { ...identity, rowId: 'second', phoneModelIds: [17] }])
  })
  it('keeps failed input and retries the identical request id and preview token', async () => {
    api.addCatalogSelection.mockRejectedValueOnce({code:'ECONNABORTED'}).mockResolvedValue(result)
    await start(); await chooseModels(); await click('保存选样'); const original = api.addCatalogSelection.mock.calls[0][0]
    expect(wrapper.text()).toContain('保存结果待核对'); await click('核对保存结果')
    expect(api.addCatalogSelection.mock.calls[1][0]).toEqual(original); expect(wrapper.emitted('added')).toHaveLength(1)
  })
  it('blocks double submission while the first request is pending', async () => {
    let resolve!: (value: unknown) => void; api.addCatalogSelection.mockImplementation(() => new Promise(done => { resolve = done }))
    await start(); await chooseModels(); await click('保存选样'); expect(button('保存选样').attributes('disabled')).toBeDefined()
    await (wrapper.vm as unknown as { submit: () => Promise<void> }).submit(); expect(api.addCatalogSelection).toHaveBeenCalledTimes(1); resolve(result); await flushPromises()
  })
  it('does not allow direct preview or submit handlers after write permission is withdrawn', async () => {
    await start(); await chooseModels(); await wrapper.setProps({ canWrite: false }); await (wrapper.vm as unknown as { submit: () => Promise<void> }).submit()
    expect(api.addCatalogSelection).not.toHaveBeenCalled()
  })
  it('keeps an invalid or readonly workbench destination as an explicit error instead of falling back to another directory',async()=>{api.previewCatalogSelection.mockResolvedValue({canAdd:false,previewToken:'',items:[],messages:['目标选品目录属于其他供应商或不可修改']});await start();await wrapper.setProps({targetSelectionId:88,targetVersion:4});await chooseModels([18]);await click('保存选样');expect(wrapper.text()).toContain('目标选品目录属于其他供应商或不可修改');expect(api.previewCatalogSelection.mock.calls[0][0]).toMatchObject({targetSelectionId:88,targetVersion:4,targetMode:'supplier_directory'});expect(api.addCatalogSelection).not.toHaveBeenCalled();expect(api.selectionPage).not.toHaveBeenCalled()})
  it('ignores an old submit result after closing or changing supplier context', async () => {
    let resolve!: (value: unknown) => void; api.addCatalogSelection.mockImplementation(() => new Promise(done => { resolve = done }))
    await start(); await chooseModels(); await click('保存选样'); await wrapper.setProps({ modelValue: false, supplierCode: 'ANOTHER' }); resolve(result); await flushPromises()
    expect(wrapper.emitted('added')).toBeUndefined()
  })
  it('reconciles an unknown completed save with the exact original auto-target body and token without re-previewing a now-changed directory',async()=>{api.previewCatalogSelection.mockResolvedValueOnce({canAdd:true,previewToken:'before-create',targetSelectionId:0,targetVersion:0,items:[],messages:[]}).mockResolvedValue({canAdd:true,previewToken:'after-create-different-token',targetSelectionId:77,targetVersion:99,items:[],messages:[]});api.addCatalogSelection.mockRejectedValueOnce({code:'ECONNABORTED'}).mockResolvedValue({...result,replayed:true});await start();await chooseModels([18]);modelRow().get('.selection-row-quantity').getComponent(ElInputNumber).vm.$emit('update:modelValue',2);await flushPromises();await click('保存选样');const input=JSON.parse(JSON.stringify(api.addCatalogSelection.mock.calls[0][0]));expect(wrapper.text()).toContain('保存结果待核对');expect(rowPicker().props('disabled')).toBe(true);expect(button('取消').attributes('disabled')).toBeDefined();await click('核对保存结果');expect(api.previewCatalogSelection).toHaveBeenCalledTimes(1);expect(api.addCatalogSelection.mock.calls[1][0]).toEqual(input);expect(input).not.toHaveProperty('targetSelectionId');expect(input).toMatchObject({targetMode:'supplier_directory',previewToken:'before-create',items:[expect.objectContaining({sampleQty:2})]});expect(wrapper.emitted('added')).toHaveLength(1)})
  it('invalidates an in-flight preview when its exact workbench destination context changes',async()=>{let resolve!:(value:unknown)=>void;api.previewCatalogSelection.mockImplementationOnce(()=>new Promise(done=>{resolve=done}));await start();await wrapper.setProps({targetSelectionId:77,targetVersion:8});await chooseModels([18]);await click('保存选样');await wrapper.setProps({targetSelectionId:88,targetVersion:9});resolve({canAdd:true,previewToken:'old-context',items:[],messages:[]});await flushPromises();expect(api.addCatalogSelection).not.toHaveBeenCalled();expect(wrapper.emitted('added')).toBeUndefined()})

  it('revalidates an explicit business error returned as HTTP200 without losing planned quantity or workbench context',async()=>{api.previewCatalogSelection.mockResolvedValueOnce({canAdd:true,previewToken:'before-conflict',items:[],messages:[]}).mockResolvedValue({canAdd:true,previewToken:'after-conflict',items:[],messages:[]});api.addCatalogSelection.mockRejectedValueOnce(new Error('来源或目标已变化，请重新核对')).mockResolvedValue(result);await start();await wrapper.setProps({targetSelectionId:77,targetVersion:8});await chooseModels([18]);modelRow().get('.selection-row-quantity').getComponent(ElInputNumber).vm.$emit('update:modelValue',2);await flushPromises();await click('保存选样');expect(wrapper.text()).toContain('来源或目标已变化');expect(rowPicker().props('modelValue')).toEqual([18]);expect(modelRow().getComponent(ElInputNumber).props('modelValue')).toBe(2);await click('保存选样');expect(api.previewCatalogSelection).toHaveBeenCalledTimes(2);expect(api.addCatalogSelection.mock.calls.map(call=>call[0].previewToken)).toEqual(['before-conflict','after-conflict']);expect(api.addCatalogSelection.mock.calls[1][0]).toMatchObject({targetSelectionId:77,targetVersion:8,targetMode:'supplier_directory',items:[expect.objectContaining({phoneModelIds:[18],sampleQty:2})]});expect(wrapper.emitted('added')).toHaveLength(1)})
  it('refuses to reconcile a pending command under a different supplier or workbench context',async()=>{api.addCatalogSelection.mockRejectedValueOnce({code:'ECONNABORTED'}).mockResolvedValue(result);await start();await wrapper.setProps({targetSelectionId:77,targetVersion:8});await chooseModels([18]);await click('保存选样');await wrapper.setProps({supplierCode:'OTHER',targetSelectionId:88,targetVersion:9,items:[source({supplierCode:'OTHER',name:'另一家供方款'})]});await click('核对保存结果');expect(wrapper.text()).toContain('待核对的保存属于原供应商或原选品目录');expect(api.addCatalogSelection).toHaveBeenCalledTimes(1);expect(api.previewCatalogSelection).toHaveBeenCalledTimes(1);expect(wrapper.emitted('added')).toBeUndefined();await wrapper.setProps({supplierCode:'SUP-REAL',targetSelectionId:77,targetVersion:8,items:[source()]});await click('核对保存结果');expect(api.addCatalogSelection.mock.calls[1][0]).toEqual(api.addCatalogSelection.mock.calls[0][0]);expect(wrapper.emitted('added')).toHaveLength(1)})

})
