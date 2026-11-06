import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, ref } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import ElementPlus, { ElMessageBox, ElSelect } from 'element-plus'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import UnitCostSheet from '../components/UnitCostSheet.vue'
import { useUserStore } from '@/stores/user'
import { setLanguage } from '@/locales'
import { researchAction, previewUnitCost, type ProductResearchDetail, type UnitCostSheet as Sheet } from '@/api/modules/productResearch'

vi.mock('@/api/modules/productResearch', () => ({ researchAction: vi.fn(), previewUnitCost: vi.fn() }))
let wrapper: VueWrapper
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value))
const sourceDraft = (): Sheet => ({ title: '来源核算草稿', currency: '', taxBasis: 'source', bomId: 10, routeId: 31,
  sourceOutputQty: 10, references: [{ type: 'bom', id: 10 }], totals: { base: null }, complete: false, missing: ['核算币种待确认'],
  rows: [{ id: 'material', category: 'material', name: '来源物料', mode: 'usage', unitPrice: null, quantity: null,
    sourceQuantity: 0.00000001, sourceOutputQty: 10, warning: '请核对单件用量' }] })
const simpleDraft = (): Sheet => ({ title: '测试核算', currency: 'CNY', taxBasis: 'net', references: [], totals: { base: null },
  rows: [0, 1].map(index => ({ id: `row-${index}`, category: 'material', name: `材料${index + 1}`, mode: 'unit', unitPrice: null, taxIncluded: null, taxRate: null })) })
async function start(permissions = ['finance:read', 'finance:write'], draft = sourceDraft(), workflow: Record<string, unknown> = {},embedded=false,costScopeId='') {
  const pinia = createPinia(); setActivePinia(pinia); useUserStore().permissions = permissions
  const detail = ref({ productId: 42, status: 'developing', canWrite: true, workflow: { version: 3, unitCostDraft: clone(draft), ...workflow }, materials: [], bomRoutes: [], events: [] } as unknown as ProductResearchDetail)
  vi.mocked(previewUnitCost).mockImplementation(async(_id,input)=>({...clone(draft),...(input.sourceOutlay?{sourceOutlay:clone(input.sourceOutlay)}:{})}))
  vi.mocked(researchAction).mockImplementation(async (_id, action, _version, input) => {
    const next = clone(detail.value)
    if (action === 'unit_cost_confirm') { next.workflow.unitCostVersions = [{ ...clone(input as Sheet), id: 1, status: 'confirmed' }]; delete next.workflow.unitCostDraft }
    else if (action === 'unit_cost_delete') delete next.workflow.unitCostDraft
    else next.workflow.unitCostDraft = clone(input)
    detail.value = next; return next
  })
  const editing=ref(false)
  const Host = defineComponent({ setup: () => () => h(UnitCostSheet, { detail: detail.value,embedded,editing:editing.value,costScopeId }) })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: Host }, { path: '/other', component: { template: '<p>other</p>' } }] })
  await router.push('/'); await router.isReady()
  wrapper = mount(RouterView, { attachTo: document.body, global: { plugins: [pinia, router, ElementPlus] } }); await flushPromises()
  return { detail, router, editing, component: wrapper.getComponent(UnitCostSheet), currency: wrapper.findAllComponents(ElSelect).find(select => select.props('placeholder') === '币种待确认')! }
}
const button = (text: string) => {
  const found = wrapper.findAll('button').find(item => item.text() === text)
  expect(found, `button ${text}`).toBeTruthy(); return found!
}
const cell = (row: number, field: string) => wrapper.get(`[data-cell="${row}:${field}"]`)
async function check(row: number) { await wrapper.get(`input[aria-label="勾选成本第${row + 1}行"]`).setValue(true) }
async function paste(row: number, field: string, text: string) { await cell(row, field).trigger('paste', { clipboardData: { getData: () => text } }); await flushPromises() }
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
beforeEach(() => { setLanguage('zh-CN'); vi.clearAllMocks(); vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any) })
afterEach(() => { wrapper?.unmount(); vi.useRealTimers(); vi.restoreAllMocks(); setLanguage('zh-CN') })

it('keeps the embedded sheet read-only until Edit, saves linked costs in the original draft and can cancel local changes', async()=>{
  const draft=simpleDraft();draft.currency='CNY';draft.rows[0].unitPrice=2
  const {component,editing}=await start(undefined,draft,{},true)
  expect(wrapper.find('[data-cell]').exists()).toBe(false)
  editing.value=true;await flushPromises();component.vm.beginEdit();await flushPromises()
  await cell(0,'unitPrice').setValue('3');component.vm.cancelEdit();await flushPromises()
  expect((cell(0,'unitPrice').element as HTMLInputElement).value).toBe('2')
  await component.vm.saveProjectCosts();await flushPromises()
  expect(researchAction).toHaveBeenCalledWith(42,'unit_cost_save',3,expect.objectContaining({useProjectInvestment:true,rows:[expect.objectContaining({unitPrice:2}),expect.anything()]}))
  expect(ElMessageBox.confirm).not.toHaveBeenCalled()
})
it('edits amortisation quantity once while source amounts remain read-only',async()=>{
  const draft=simpleDraft();draft.rows=[{id:'project-investment-tooling',category:'tooling',name:'模具摊销',mode:'divide',unitPrice:12000,allocationQty:null,investmentCategories:['mold']},{id:'project-investment-development',category:'development',name:'研发摊销',mode:'divide',unitPrice:300,allocationQty:null,investmentCategories:['design','sample','other']}]
  const {component,editing}=await start(undefined,draft,{},true);editing.value=true;await flushPromises();component.vm.beginEdit();await flushPromises()
  expect(cell(0,'unitPrice').attributes('disabled')).toBeDefined()
  await wrapper.get('input[aria-label="统一分摊数量"]').setValue('30000');await component.vm.saveProjectCosts();await flushPromises()
  expect(researchAction).toHaveBeenCalledWith(42,'unit_cost_save',3,expect.objectContaining({rows:[expect.objectContaining({allocationQty:30000,unitPrice:12000}),expect.objectContaining({allocationQty:30000,unitPrice:300})]}))
})
it('keeps a confirmed linked version frozen and reads newer project money only after Edit creates a new draft',async()=>{
  const confirmed:Sheet={title:'历史确认版本',currency:'CNY',taxBasis:'source',references:[],id:1,status:'confirmed',confirmedAt:'2026-11-01',confirmedBy:'合成财务',useProjectInvestment:true,totals:{base:1},rows:[{id:'project-investment-tooling',category:'tooling',name:'历史模具',mode:'divide',unitPrice:100,allocationQty:100,unitCost:1,investmentCategories:['mold']}]}
  const {component,editing,detail}=await start(undefined,confirmed,{unitCostDraft:undefined,unitCostVersions:[confirmed]},true)
  expect(previewUnitCost).not.toHaveBeenCalled();expect(wrapper.text()).toContain('100.00')
  vi.mocked(previewUnitCost).mockResolvedValue({...clone(confirmed),rows:[{...confirmed.rows[0],unitPrice:130,unitCost:1.3}],totals:{base:1.3}})
  editing.value=true;await flushPromises();component.vm.beginEdit();await flushPromises()
  expect(previewUnitCost).toHaveBeenCalledWith(42,expect.objectContaining({useProjectInvestment:true,rows:[expect.objectContaining({unitPrice:100})]}))
  expect((cell(0,'unitPrice').element as HTMLInputElement).value).toBe('130')
  expect((detail.value.workflow.unitCostVersions as Sheet[])[0].rows[0].unitPrice).toBe(100)
  expect((detail.value.workflow.unitCostVersions as Sheet[])[0].confirmedBy).toBe('合成财务')
})
it('routes new mould expenses to the top table instead of accepting duplicate manual amortisation rows',async()=>{
  const {component,editing}=await start(undefined,simpleDraft(),{},true);editing.value=true;await flushPromises();component.vm.beginEdit();await flushPromises()
  await cell(0,'category').setValue('tooling')
  expect(wrapper.text()).toContain('模具及研发费用请在上方编辑')
  await component.vm.saveProjectCosts();await flushPromises()
  expect(researchAction).toHaveBeenCalledWith(42,'unit_cost_save',3,expect.objectContaining({rows:[expect.objectContaining({category:'material'}),expect.anything()]}))
})

it('keeps an imported unknown currency, original output and source evidence visible without labelling it CNY', async () => {
  const { currency } = await start()
  expect(currency.props('modelValue')).toBe(''); expect(currency.props('placeholder')).toBe('币种待确认')
  expect(wrapper.text()).toContain('来源 BOM 基准产量：10'); expect(wrapper.text()).toContain('不将批量用量直接当成单件成本')
  expect(wrapper.text()).toContain('核算币种待确认'); expect(wrapper.find('[data-label="币种待确认 单件成本"]').exists()).toBe(true)
  expect(wrapper.find('[data-label="人民币单价"]').exists()).toBe(false)
  expect(wrapper.find('.cell-conversion').exists()).toBe(false)
})
it('saves explicitly selected currency while preserving unknown usage, exact source quantity and references', async () => {
  const { currency } = await start(); currency.vm.$emit('update:modelValue', 'USD'); await flushPromises()
  await button('保存成本草稿').trigger('click'); await flushPromises()
  expect(researchAction).toHaveBeenCalledWith(42, 'unit_cost_save', 3, expect.objectContaining({ currency: 'USD', sourceOutputQty: 10, references: [{ type: 'bom', id: 10 }], rows: [expect.objectContaining({ quantity: null, sourceQuantity: 0.00000001, sourceOutputQty: 10 })] }))
})
it('does not turn engineering or read access into financial maintenance authority', async () => {
  const { currency } = await start(['finance:read', 'bom:write', 'bom:publish'])
  expect(currency.find('input').attributes('disabled')).toBeDefined()
  expect(wrapper.findAll('button').some(item => ['保存成本草稿', '确认成本版本'].includes(item.text()))).toBe(false)
  expect(wrapper.find('[data-cell]').exists()).toBe(false); expect(researchAction).not.toHaveBeenCalled()
})
it('pastes Excel rows directly into the draft, preserves IDs and can undo the whole rectangle without saving', async () => {
  await start(undefined, simpleDraft())
  await paste(0, 'name', '材料甲\tunit\t1.234567\n材料乙\tunit\t0')
  expect((cell(0, 'name').element as HTMLInputElement).value).toBe('材料甲')
  expect((cell(1, 'unitPrice').element as HTMLInputElement).value).toBe('0')
  expect(wrapper.findAll('[data-cost-row]').map(row => row.attributes('data-cost-row'))).toEqual(['row-0', 'row-1'])
  expect(researchAction).not.toHaveBeenCalled()
  await button('撤销上一步').trigger('click')
  expect((cell(0, 'name').element as HTMLInputElement).value).toBe('材料1')
  expect((cell(0, 'unitPrice').element as HTMLInputElement).value).toBe('')
  expect(button('撤销上一步').attributes('disabled')).toBeDefined()
})
it('rejects an invalid later Excel cell atomically and leaves every existing value intact', async () => {
  await start(undefined, simpleDraft())
  await paste(0, 'name', '有效项目\tunit\t12\n错误项目\tunit\t=2+3')
  expect(wrapper.get('[role="alert"]').text()).toContain('第 2 行')
  expect((cell(0, 'name').element as HTMLInputElement).value).toBe('材料1')
  expect((cell(1, 'unitPrice').element as HTMLInputElement).value).toBe('')
  expect(researchAction).not.toHaveBeenCalled()
})
it('preserves blank pasted cells but lets a manually cleared number become null rather than zero', async () => {
  const draft = simpleDraft(); draft.rows[0].unitPrice = 9
  await start(undefined, draft); await paste(0, 'name', '\tunit\t\n')
  expect((cell(0, 'name').element as HTMLInputElement).value).toBe('材料1')
  expect((cell(0, 'unitPrice').element as HTMLInputElement).value).toBe('9')
  await cell(0, 'unitPrice').setValue(''); await button('保存成本草稿').trigger('click'); await flushPromises()
  expect(vi.mocked(researchAction).mock.calls[0][3]).toMatchObject({ rows: [{ id: 'row-0', unitPrice: null }, { id: 'row-1' }] })
})
it('edits all three modes without confusing an expense total with its calculated per-unit result', async () => {
  const draft = simpleDraft(); draft.rows[0].quantity = 3; draft.rows[0].allocationQty = 80; draft.rows[0].lossPct = 4
  await start(undefined, draft)
  await cell(0, 'mode').setValue('divide'); expect(cell(0, 'unitPrice').attributes('aria-label')).toBe('费用总额')
  expect(cell(0, 'basisQuantity').attributes('aria-label')).toBe('分摊数量')
  await cell(0, 'unitPrice').setValue('1200'); await cell(0, 'basisQuantity').setValue('100')
  await cell(0, 'mode').setValue('usage'); expect((cell(0, 'basisQuantity').element as HTMLInputElement).value).toBe('3')
  await cell(0, 'mode').setValue('unit'); expect(wrapper.find('[data-cell="0:basisQuantity"]').exists()).toBe(false)
  await button('保存成本草稿').trigger('click'); await flushPromises()
  expect(vi.mocked(researchAction).mock.calls[0][3]).toMatchObject({ rows: [{ mode: 'unit', unitPrice: 1200, quantity: 3, allocationQty: 100, lossPct: 4 }, {}] })
})
it('copies, deletes and restores selected rows locally without replacing source row identities', async () => {
  await start(undefined, simpleDraft()); await check(0); await button('复制选中').trigger('click')
  const ids = wrapper.findAll('[data-cost-row]').map(row => row.attributes('data-cost-row'))
  expect(ids).toHaveLength(3); expect(ids[0]).toBe('row-0'); expect(ids[2]).toBe('row-1'); expect(ids[1]).not.toBe('row-0')
  await button('删除选中').trigger('click'); expect(wrapper.findAll('[data-cost-row]')).toHaveLength(2)
  await button('撤销上一步').trigger('click'); expect(wrapper.findAll('[data-cost-row]')).toHaveLength(3)
  expect(researchAction).not.toHaveBeenCalled(); expect(ElMessageBox.confirm).not.toHaveBeenCalled()
})
it('applies a specified field to checked rows and fills from the first checked row without touching other fields', async () => {
  const draft = simpleDraft(); draft.rows[0].unitPrice = 2.5; draft.rows[1].unitPrice = 7
  await start(undefined, draft); await check(0); await check(1)
  await wrapper.get('select[aria-label="批量字段"]').setValue('unitPrice')
  await button('按首行向下填充').trigger('click')
  expect((cell(1, 'unitPrice').element as HTMLInputElement).value).toBe('2.5')
  expect((cell(1, 'name').element as HTMLInputElement).value).toBe('材料2')
  await wrapper.get('select[aria-label="批量字段"]').setValue('taxIncluded')
  await wrapper.get('select[aria-label="批量内容"]').setValue('false'); await button('应用到选中行').trigger('click')
  await wrapper.get('button[aria-label="第1行计算依据"]').trigger('click')
  expect((wrapper.get('select[aria-label="原价是否含税"]').element as HTMLSelectElement).value).toBe('false')
  await wrapper.get('select[aria-label="批量内容"]').setValue(''); await button('应用到选中行').trigger('click')
  await button('保存成本草稿').trigger('click'); await flushPromises()
  expect(vi.mocked(researchAction).mock.calls[0][3]).toMatchObject({ rows: [{ name: '材料1', taxIncluded: null }, { name: '材料2', taxIncluded: null }] })
})
it('keeps long descriptions and original tax/FX values in expandable detail while rendering one readable result table', async () => {
  const draft = simpleDraft(); Object.assign(draft, { displayCurrency: 'USD', exchangeRate: 0.15, exchangeRateDate: '2026-11-01', totals: { manufacturing: 2, base: 3, freight_sea: 4, freight_air: 5 } })
  draft.rows[0].description = '长说明'.repeat(300); draft.rows[0].taxIncluded = true; draft.rows[0].taxRate = 13
  await start(undefined, draft)
  expect(wrapper.findAll('table')).toHaveLength(1); expect(wrapper.find('.row-details').exists()).toBe(false)
  expect(wrapper.get('.cost-overview').text()).toContain('含海运合计 4.00'); expect(wrapper.get('.cost-overview').text()).toContain('含空运合计 5.00')
  expect(wrapper.findAll('.cell-conversion')).toHaveLength(2)
  await wrapper.get('button[aria-label="第1行计算依据"]').trigger('click')
  expect((wrapper.get('textarea[aria-label="费用说明"]').element as HTMLTextAreaElement).value).toHaveLength(900)
  await button('保存成本草稿').trigger('click'); await flushPromises()
  expect(vi.mocked(researchAction).mock.calls[0][3]).toMatchObject({ exchangeRateDate: '2026-11-01', exchangeRate: 0.15, rows: [{ taxRate: 13, taxIncluded: true }, {}] })
})
it('uses Enter down a column and Tab across editable cells', async () => {
  await start(undefined, simpleDraft())
  const longName = '复合材质外壳及表面处理费用'.repeat(5)
  await cell(0, 'name').setValue(longName)
  expect(wrapper.get('.name-editor').attributes('data-value')).toBe(longName + ' ')
  const first = cell(0, 'name'); expect(first.element.tagName).toBe('TEXTAREA'); (first.element as HTMLTextAreaElement).focus(); await first.trigger('keydown', { key: 'Enter' })
  expect(document.activeElement).toBe(cell(1, 'name').element)
  await cell(1, 'name').trigger('keydown', { key: 'Tab' }); expect(document.activeElement).toBe(cell(1, 'mode').element)
})
it('invalidates a pending preview immediately on editing and automatically requests the current draft after 450ms', async () => {
  await start(undefined, simpleDraft()); const old = deferred<Sheet>(), fresh = deferred<Sheet>()
  vi.mocked(previewUnitCost).mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
  await button('重新核算').trigger('click'); vi.useFakeTimers()
  await cell(0, 'unitPrice').setValue('12')
  old.resolve({ ...simpleDraft(), totals: { base: 777 } }); await flushPromises()
  expect(wrapper.get('.cost-overview').text()).not.toContain('777.00')
  await vi.advanceTimersByTimeAsync(450); expect(previewUnitCost).toHaveBeenCalledTimes(2)
  expect(vi.mocked(previewUnitCost).mock.calls[1][1].rows[0].unitPrice).toBe(12)
  fresh.resolve({ ...simpleDraft(), totals: { base: 12 } }); await flushPromises()
  expect(wrapper.get('.cost-overview').text()).toContain('12.00')
})
it('locks row inputs and batch operations during save so a late receipt cannot overwrite new typing', async () => {
  const { detail } = await start(undefined, simpleDraft()); await check(0)
  const receipt = deferred<ProductResearchDetail>(); vi.mocked(researchAction).mockReturnValueOnce(receipt.promise)
  await button('保存成本草稿').trigger('click'); await flushPromises()
  expect(cell(0, 'name').attributes('disabled')).toBeDefined(); expect(cell(0, 'unitPrice').attributes('disabled')).toBeDefined()
  expect(button('新增明细').attributes('disabled')).toBeDefined(); expect(button('复制选中').attributes('disabled')).toBeDefined()
  expect(wrapper.get('select[aria-label="批量字段"]').attributes('disabled')).toBeDefined()
  await cell(0, 'name').setValue('不应写入'); await button('保存成本草稿').trigger('click')
  expect(researchAction).toHaveBeenCalledTimes(1)
  expect(vi.mocked(researchAction).mock.calls[0][3]).toMatchObject({ rows: [{ name: '材料1' }, {}] })
  receipt.resolve(detail.value); await flushPromises(); expect(cell(0, 'name').attributes('disabled')).toBeUndefined()
})
it('keeps failed saves editable and preserves the entered amount for retry', async () => {
  await start(undefined, simpleDraft()); await cell(0, 'unitPrice').setValue('1.234567')
  vi.mocked(researchAction).mockRejectedValueOnce(Error('项目已被其他操作更新'))
  await button('保存成本草稿').trigger('click'); await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('项目已被其他操作更新')
  expect((cell(0, 'unitPrice').element as HTMLInputElement).value).toBe('1.234567'); expect(cell(0, 'unitPrice').attributes('disabled')).toBeUndefined()
})
it('keeps confirmed versions read-only, retains explicit confirmation and protects leaving an unsaved draft', async () => {
  const draft = simpleDraft(); const { router } = await start(undefined, draft, { unitCostVersions: [{ ...clone(draft), id: 4, status: 'confirmed' }] })
  wrapper.findAllComponents(ElSelect)[0].vm.$emit('change', '4'); await flushPromises()
  expect(wrapper.find('[data-cell]').exists()).toBe(false); expect(button('复制为新版本').exists()).toBe(true)
  await button('复制为新版本').trigger('click'); await flushPromises(); expect(wrapper.find('[data-cell]').exists()).toBe(true)
  vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel'); await router.push('/other')
  expect(router.currentRoute.value.path).toBe('/')
  await button('确认成本版本').trigger('click'); await flushPromises()
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('BOM路线或供方报价'), '确认单件成本')
  expect(researchAction).toHaveBeenCalledWith(42, 'unit_cost_confirm', 3, expect.anything())
})

it('continues automatic calculation after cancelling a version confirmation instead of requiring manual recalculate', async () => {
  await start(undefined, simpleDraft()); vi.useFakeTimers()
  await cell(0, 'unitPrice').setValue('8'); vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
  await button('确认成本版本').trigger('click'); await flushPromises()
  expect(researchAction).not.toHaveBeenCalled(); expect(previewUnitCost).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(450); await flushPromises()
  expect(previewUnitCost).toHaveBeenCalledOnce(); expect(vi.mocked(previewUnitCost).mock.calls[0][1].rows[0].unitPrice).toBe(8)
})

async function addExtraColumn(label: string, type: 'text' | 'number' = 'text') {
  await wrapper.get('input[aria-label="新增列名称"]').setValue(label)
  await wrapper.get('select[aria-label="补充列类型"]').setValue(type)
  await wrapper.get('form.new-column').trigger('submit'); await flushPromises()
  return wrapper.findAll('[data-extra-column]').at(-1)!.attributes('data-extra-column')!
}
const supplementalDraft = (): Sheet => {
  const draft = simpleDraft()
  draft.extraColumns = [{ id: 'pack', label: '包装方式', type: 'text' }, { id: 'hidden', label: '原始说明', type: 'text', hidden: true }, { id: 'weight', label: '参考重量(g)', type: 'number' }]
  draft.rows[0].extraValues = { pack: '纸袋', hidden: '保留的原始资料', weight: 1.25 }
  draft.rows[1].extraValues = { pack: '原包装', hidden: '第二行原始资料', weight: null }
  return draft
}
it('adds text and numeric supplemental columns through the form and preserves their values after saving and reopening', async () => {
  const { detail } = await start(undefined, simpleDraft())
  const pack = await addExtraColumn('包装方式'), weight = await addExtraColumn('参考重量(g)', 'number')
  expect(wrapper.text()).toContain('补充列只记录资料，不计入成本；新增费用请新增明细行。')
  await cell(0, `extra:${pack}`).setValue('纸袋／每袋2件'); await cell(0, `extra:${weight}`).setValue('-0.25')
  await button('保存成本草稿').trigger('click'); await flushPromises()
  const saved = clone(detail.value.workflow.unitCostDraft as Sheet)
  expect(saved.extraColumns).toEqual([{ id: pack, label: '包装方式', type: 'text' }, { id: weight, label: '参考重量(g)', type: 'number' }])
  expect(saved.rows[0]).toMatchObject({ id: 'row-0', unitPrice: null, extraValues: { [pack]: '纸袋／每袋2件', [weight]: -0.25 } })
  wrapper.unmount(); await start(undefined, saved)
  expect((cell(0, `extra:${pack}`).element as HTMLTextAreaElement).value).toBe('纸袋／每袋2件')
  expect((cell(0, `extra:${weight}`).element as HTMLInputElement).value).toBe('-0.25')
})
it('renames, hides and shows a column without deleting its values and can undo the presentation change', async () => {
  const { detail } = await start(undefined, supplementalDraft())
  await wrapper.get('[data-extra-column="pack"] input[data-column-label]').setValue('包装备注')
  expect(wrapper.get('[data-extra-field="pack"]>span').text()).toContain('包装备注')
  await check(0); await wrapper.get('select[aria-label="批量字段"]').setValue('extra:pack')
  await wrapper.get('[data-extra-column="pack"] input[type="checkbox"]').setValue(false)
  expect((wrapper.get('select[aria-label="批量字段"]').element as HTMLSelectElement).value).toBe('taxIncluded')
  expect(wrapper.find('[data-cell="0:extra:pack"]').exists()).toBe(false)
  await button('保存成本草稿').trigger('click'); await flushPromises()
  expect((detail.value.workflow.unitCostDraft as Sheet).extraColumns?.find(column => column.id === 'pack')).toMatchObject({ label: '包装备注', hidden: true })
  expect((detail.value.workflow.unitCostDraft as Sheet).rows[0].extraValues?.pack).toBe('纸袋')
  await wrapper.get('[data-extra-column="pack"] input[type="checkbox"]').setValue(true)
  expect((cell(0, 'extra:pack').element as HTMLTextAreaElement).value).toBe('纸袋')
  await button('撤销上一步').trigger('click'); expect(wrapper.find('[data-cell="0:extra:pack"]').exists()).toBe(false)
})
it('makes existing optional fields permanent below the core row without duplicating their detail editors', async () => {
  const draft = simpleDraft(); draft.rows[0].mode = 'usage'; draft.rows[0].quantity = 1
  await start(undefined, draft)
  for (const label of ['原价含税', '税率%', '损耗%', '费用说明', '备注']) await wrapper.get(`input[aria-label="常驻列：${label}"]`).setValue(true)
  expect(wrapper.findAll('.row-columns')).toHaveLength(2)
  await wrapper.get('button[aria-label="第1行计算依据"]').trigger('click')
  for (const field of ['taxIncluded', 'taxRate', 'lossPct', 'description', 'remark']) expect(wrapper.findAll(`[data-cell="0:${field}"]`)).toHaveLength(1)
  await cell(0, 'taxRate').setValue('0'); await button('保存成本草稿').trigger('click'); await flushPromises()
  expect(vi.mocked(researchAction).mock.calls[0][3]).toMatchObject({ visibleOptionalColumns: ['taxIncluded', 'taxRate', 'lossPct', 'description', 'remark'], rows: [{ taxRate: 0 }, {}] })
})
it('pastes and fills supplemental cells through the existing tools while leaving hidden intermediate columns and cost amounts intact', async () => {
  const { detail } = await start(undefined, supplementalDraft())
  await paste(0, 'extra:pack', '袋A\t0\n袋B\t-1.25')
  expect((cell(1, 'extra:weight').element as HTMLInputElement).value).toBe('-1.25')
  await check(0); await check(1); await wrapper.get('select[aria-label="批量字段"]').setValue('extra:pack')
  await button('按首行向下填充').trigger('click')
  expect((cell(1, 'extra:pack').element as HTMLTextAreaElement).value).toBe('袋A')
  await button('保存成本草稿').trigger('click'); await flushPromises()
  const saved = detail.value.workflow.unitCostDraft as Sheet
  expect(saved.rows[0]).toMatchObject({ unitPrice: null, extraValues: { pack: '袋A', hidden: '保留的原始资料', weight: 0 } })
  expect(saved.rows[1]).toMatchObject({ unitPrice: null, extraValues: { pack: '袋A', hidden: '第二行原始资料', weight: -1.25 } })
})
it('keeps hidden supplemental values viewable in readonly history and preserves them when copying that version', async () => {
  const draft = supplementalDraft(); const { detail } = await start(undefined, draft, { unitCostVersions: [{ ...clone(draft), id: 4, status: 'confirmed' }] })
  wrapper.findAllComponents(ElSelect)[0].vm.$emit('change', '4'); await flushPromises()
  await wrapper.get('button[aria-label="第1行计算依据"]').trigger('click')
  expect(wrapper.get('[data-hidden-extra="hidden"]').text()).toContain('保留的原始资料')
  expect(wrapper.find('[data-cell]').exists()).toBe(false)
  expect(wrapper.get('[data-extra-column="hidden"] input[type="checkbox"]').attributes('disabled')).toBeDefined()
  await button('复制为新版本').trigger('click'); await flushPromises()
  await wrapper.get('[data-extra-column="hidden"] input[type="checkbox"]').setValue(true)
  expect((cell(0, 'extra:hidden').element as HTMLTextAreaElement).value).toBe('保留的原始资料')
  await button('保存成本草稿').trigger('click'); await flushPromises()
  expect((detail.value.workflow.unitCostDraft as Sheet).rows[0].extraValues).toEqual(draft.rows[0].extraValues)
})
it('rejects conflicting column names without replacing existing columns or editing a readonly financial view', async () => {
  await start(undefined, supplementalDraft()); await addExtraColumn('包装方式')
  expect(wrapper.get('[role="alert"]').text()).toContain('重复'); expect(wrapper.findAll('[data-extra-column]')).toHaveLength(3)
  wrapper.unmount(); await start(['finance:read'], supplementalDraft())
  expect(wrapper.find('form.new-column').exists()).toBe(false)
  expect(wrapper.get('[data-extra-column="pack"] input[data-column-label]').attributes('disabled')).toBeDefined()
  expect(wrapper.find('[data-cell]').exists()).toBe(false); expect(researchAction).not.toHaveBeenCalled()
})
it('locks column definitions and supplemental editors alongside core inputs while a save is pending', async () => {
  const { detail } = await start(undefined, supplementalDraft()), receipt = deferred<ProductResearchDetail>()
  vi.mocked(researchAction).mockReturnValueOnce(receipt.promise)
  await button('保存成本草稿').trigger('click'); await flushPromises()
  expect(wrapper.get('input[aria-label="新增列名称"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('[data-extra-column="pack"] input[data-column-label]').attributes('disabled')).toBeDefined()
  expect(cell(0, 'extra:pack').attributes('disabled')).toBeDefined()
  receipt.resolve(detail.value); await flushPromises()
})
it.each([19, 0, 21])('retains a synthetic %i-line existing project shape without inventing tax, BOM, route or a confirmed cost', async count => {
  const draft: Sheet = { title: 'QA合成既有结构', currency: 'CNY', displayCurrency: 'MXN', exchangeRate: 2, exchangeRateDate: null, taxBasis: 'source', bomId: null, routeId: null, references: [], totals: { base: null }, rows: Array.from({ length: count }, (_, index) => ({ id: `shape-${index}`, category: ['material', 'process', 'tooling', 'tool_transport', 'development'][index % 5], name: `QA合成费用${index + 1}`, mode: index % 2 ? 'unit' : 'divide', unitPrice: index % 2 ? 1 : 120, allocationQty: index % 2 ? null : 100, taxIncluded: null, taxRate: null })) }
  await start(undefined, draft); expect(wrapper.findAll('[data-cost-row]')).toHaveLength(count)
  await button('保存成本草稿').trigger('click'); await flushPromises()
  const sent = vi.mocked(researchAction).mock.calls[0]
  expect(sent[1]).toBe('unit_cost_save'); expect(sent[3]).toMatchObject({ currency: 'CNY', displayCurrency: 'MXN', taxBasis: 'source', exchangeRateDate: null, bomId: null, routeId: null })
  const savedRows = (sent[3] as Sheet).rows
  expect(savedRows).toHaveLength(count); expect(savedRows.every(row => row.taxRate === null && row.taxIncluded === null)).toBe(true)
  expect(vi.mocked(researchAction).mock.calls.some(call => call[1] === 'unit_cost_confirm')).toBe(false)
})

it('keeps user-defined column labels and values unchanged when switching the interface language', async () => {
  await start(undefined, supplementalDraft()); setLanguage('es'); await flushPromises()
  expect(wrapper.get('[data-extra-field="pack"]>span').text()).toContain('包装方式')
  expect((cell(0, 'extra:pack').element as HTMLTextAreaElement).value).toBe('纸袋')
})
it('explains that a BOM replacement retains column names and rebuilds original values in the existing single confirmation', async () => {
  await start(); vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
  await button('从BOM带入明细').trigger('click'); await flushPromises()
  expect(ElMessageBox.confirm).toHaveBeenCalledOnce()
  expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('保留补充列名称，原明细及补充值将重建'), '从BOM带入')
  expect(researchAction).not.toHaveBeenCalled()
})

it('uses the latest confirmed version in the full cost page when there is no draft, without duplicating save controls',async()=>{const confirmed={...simpleDraft(),id:5,status:'confirmed' as const,totals:{manufacturing:2,base:3}};const {component}=await start(undefined,simpleDraft(),{unitCostDraft:undefined,unitCostVersions:[confirmed]},true);expect((component.vm as any).overview).toMatchObject({selected:'5',manufacturing:2,base:3});expect(component.find('.cost-overview').exists()).toBe(false);expect(component.findAll('button').some(b=>b.text()==='保存成本草稿')).toBe(false)})

it('edits an original estimate in place and saves its independent divisor and batch source in the same draft',async()=>{
 const draft=simpleDraft();draft.currency='CNY';draft.taxBasis='source';draft.rows=[{id:'original-core',name:'合成原模仁',category:'tooling',mode:'divide',unitPrice:900,allocationQty:300,investmentFallback:true}];draft.investmentOriginalRows=clone(draft.rows);draft.sourceOutlay={currency:'CNY',scope:'合成批量估算',rows:[{name:'材料',unitPrice:2,quantity:100,transportAmount:20}]}
 const {component,editing}=await start(undefined,draft,{},true);editing.value=true;await flushPromises();component.vm.editInvestmentEstimate('original-core','950');await component.vm.saveProjectCosts();await flushPromises()
 expect(researchAction).toHaveBeenCalledWith(42,'unit_cost_save',3,expect.objectContaining({rows:[expect.objectContaining({id:'original-core',unitPrice:950,allocationQty:300})],investmentOriginalRows:[expect.objectContaining({unitPrice:900})],sourceOutlay:expect.objectContaining({scope:'合成批量估算'})}));expect(wrapper.text()).not.toContain('模具及研发费用请在上方编辑')
})

it('saves and reopens the selected cost group without reading a sibling or the project sheet',async()=>{
  const a='11111111-1111-4111-8111-111111111111',b='22222222-2222-4222-8222-222222222222'
  const draft=simpleDraft();draft.rows[0].unitPrice=5
  const {component,editing}=await start(undefined,draft,{},true,a);editing.value=true;await flushPromises()
  const other={...simpleDraft(),title:'Pro Max',rows:[{...draft.rows[0],unitPrice:7}]}
  vi.mocked(researchAction).mockResolvedValueOnce({productId:42,workflow:{version:4,unitCostDraft:{...draft,title:'项目原核算'},costScopes:[{id:a,name:'Pro',phoneModelIds:[1],unitCostDraft:{...draft,title:'Pro已保存'}},{id:b,name:'Pro Max',phoneModelIds:[2],unitCostDraft:other}]}} as any)
  await cell(0,'unitPrice').setValue('6');await component.vm.saveProjectCosts();await flushPromises()
  expect(researchAction).toHaveBeenCalledWith(42,'unit_cost_save',3,expect.objectContaining({costScopeId:a,rows:[expect.objectContaining({unitPrice:6}),expect.anything()]}))
  expect(wrapper.get('[aria-label="核算名称"]').element).toHaveProperty('value','Pro已保存')
  expect((cell(0,'unitPrice').element as HTMLInputElement).value).toBe('5')
  expect(wrapper.text()).not.toContain('Pro Max')
})
it('keeps moved draft prices through group Edit, Save and reopening until the user explicitly links investment',async()=>{
 const pro='11111111-1111-4111-8111-111111111111';const draft={...simpleDraft(),useProjectInvestment:false,totals:{manufacturing:3,base:6.4}};draft.rows[0].unitPrice=3
 const {component,editing}=await start(undefined,draft,{},true,pro);expect(previewUnitCost).not.toHaveBeenCalled();editing.value=true;await flushPromises();component.vm.beginEdit();await flushPromises();expect(vi.mocked(previewUnitCost).mock.calls.at(-1)?.[1].useProjectInvestment).toBe(false)
 vi.mocked(researchAction).mockResolvedValueOnce({productId:42,workflow:{version:4,costScopes:[{id:pro,name:'Pro',phoneModelIds:[1],unitCostDraft:draft}]}}as any);await component.vm.saveProjectCosts();await flushPromises();expect(researchAction).toHaveBeenLastCalledWith(42,'unit_cost_save',3,expect.objectContaining({costScopeId:pro,useProjectInvestment:false,rows:expect.arrayContaining([expect.objectContaining({unitPrice:3})])}));expect((component.vm as any).overview.base).toBe(6.4)
 const calls=vi.mocked(previewUnitCost).mock.calls.length;wrapper.unmount();await start(undefined,draft,{},true,pro);expect(vi.mocked(previewUnitCost).mock.calls.length).toBe(calls)
})
it('edits the single project source through the old draft save while a confirmed version retains its original source',async()=>{
 const source={currency:'CNY',sourceHash:'current',sourceReference:{type:'file' as const,id:70},rows:[{name:'整批材料',unitPrice:2,quantity:100,transportAmount:20}]};const old={...simpleDraft(),id:8,status:'confirmed',sourceOutlay:{...source,sourceHash:'historical',sourceReference:{type:'file' as const,id:60}}};const draft=simpleDraft();const {component,editing,detail}=await start(undefined,draft,{projectSourceOutlay:source,unitCostVersions:[old]},true);expect((component.vm as any).overview.sourceOutlay.sourceHash).toBe('current');editing.value=true;await flushPromises()
 vi.mocked(researchAction).mockImplementationOnce(async()=>{const next=clone(detail.value);next.workflow.unitCostDraft=draft;return next});await component.vm.saveProjectCosts();await flushPromises();expect(researchAction).toHaveBeenLastCalledWith(42,'unit_cost_save',3,expect.objectContaining({sourceOutlay:expect.objectContaining({sourceHash:'current'}),references:expect.arrayContaining([{type:'file',id:70}])}));expect((component.vm as any).overview.sourceOutlay.sourceHash).toBe('current')
 const version=wrapper.findAllComponents(ElSelect).find(c=>c.props('ariaLabel')==='核算版本')!;version.vm.$emit('change','8');await flushPromises();expect((component.vm as any).overview.sourceOutlay.sourceHash).toBe('historical');expect(old.sourceOutlay.sourceHash).toBe('historical')
})
it('does not label the remaining project source as a zero-price unit cost after the draft has moved away',async()=>{
 const draft={...simpleDraft(),rows:[],useProjectInvestment:false,totals:{manufacturing:0,base:0}};const {component}=await start(undefined,draft,{projectSourceOutlay:{currency:'CNY',rows:[{name:'整批来源',unitPrice:2,quantity:100,transportAmount:20}]}},true);expect((component.vm as any).overview).toMatchObject({manufacturing:null,base:null});expect((component.vm as any).overview.sourceOutlay.rows).toHaveLength(1)
})
