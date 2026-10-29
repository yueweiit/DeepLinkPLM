import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElButton, ElCheckbox, ElPagination } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ReferenceObjectSelect from '../components/ReferenceObjectSelect.vue'
import { useUserStore } from '@/stores/user'
import type { ReferenceBrowserQuery, ReferenceOption } from '@/api/modules/attachment'
const api = vi.hoisted(() => ({ getReferencePage: vi.fn(), getReferencePhonePage: vi.fn() }))
vi.mock('@/api/modules/attachment', async original => ({ ...await original<typeof import('@/api/modules/attachment')>(), ...api }))
const object = (id: number, brandId = 1): ReferenceOption => ({ type: 'product', id, partType: 'ALL', code: `PRODUCT-${id}`, name: `型号档案 ${id}`, productType: 'model_variant', productLineId: 10, productLineName: '亮甲 2.0', color: '黑色', phoneModels: [{ phoneModelId: brandId * 100, brandId, brandName: brandId === 1 ? 'Apple' : 'Samsung', modelName: brandId === 1 ? 'iPhone 17 Pro Max' : 'Galaxy S26', phoneModelCode: brandId === 1 ? '0185' : '1211' }] })
const a = object(20), b = object(21, 2)
const brands = [{ brandId: 1, name: 'Apple' }, { brandId: 2, name: 'Samsung' }], productLines = [{ productLineId: 10, name: '亮甲 2.0' }]
let catalog: ReferenceOption[], wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.clearAllMocks(); setActivePinia(createPinia()); useUserStore().token = 'reference-test'; catalog = [a, b]
  api.getReferencePage.mockImplementation(async (q: ReferenceBrowserQuery) => {
    const values = catalog.filter(row => (!q.brandId || row.phoneModels?.some(p => p.brandId === q.brandId)) && (!q.phoneModelId || row.phoneModels?.some(p => p.phoneModelId === q.phoneModelId)))
    return { content: values.slice((q.page - 1) * q.size, q.page * q.size), page: q.page, size: q.size, totalElements: values.length, totalPages: Math.ceil(values.length / q.size), brands, productLines }
  })
  api.getReferencePhonePage.mockImplementation(async (q: ReferenceBrowserQuery) => {
    const records = catalog.filter(row => !q.brandId || row.phoneModels?.some(p => p.brandId === q.brandId))
    const phones = [...new Map(records.flatMap(row => row.phoneModels || []).filter(p => p.phoneModelId).map(p => [p.phoneModelId, { ...p, referenceCount: records.filter(r => r.phoneModels?.some(v => v.phoneModelId === p.phoneModelId)).length }])).values()]
    return { content: phones.slice((q.page - 1) * q.size, q.page * q.size), totalElements: phones.length, totalPages: Math.ceil(phones.length / q.size), unlinkedCount: 0, brands, productLines }
  })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.restoreAllMocks() })
async function start(props: Record<string, unknown> = {}) { wrapper = mount(ReferenceObjectSelect, { props: { modelValue: [], ...props }, attachTo: document.body, global: { plugins: [ElementPlus], stubs: { Teleport: true } } }); await flushPromises() }
async function chooseFirstPhone() { if (wrapper!.find('.reference-phone').exists()) { await wrapper!.get('.reference-phone').trigger('click'); await flushPromises() } }
async function open(objects = true) { await wrapper!.get('.reference-trigger').trigger('click'); await flushPromises(); if (objects) await chooseFirstPhone() }
async function button(text: string) { const found = wrapper!.findAllComponents(ElButton).find(b => b.text() === text); expect(found, text).toBeTruthy(); await found!.trigger('click'); await flushPromises() }
async function brand(id: number) { await wrapper!.get(`[data-brand-id="${id}"]`).trigger('click'); await flushPromises(); await chooseFirstPhone() }
async function toggle(code: string) { const box = wrapper!.findAllComponents(ElCheckbox).find(c => c.text().includes(code))!; expect(box).toBeTruthy(); box.vm.$emit('change', !box.props('modelValue')); await flushPromises() }
async function confirm() { await button(wrapper!.findAllComponents(ElButton).find(b => b.text().startsWith('完成选择'))!.text()) }
const chosen = () => wrapper!.get('.selection-heading').text()

describe('ReferenceObjectSelect', () => {
  it('opens a grouped brand browser from one compact field and keeps product, model and SKU categories distinct', async () => {
    await start(); expect(wrapper!.find('.reference-panel').exists()).toBe(false); expect(api.getReferencePage).not.toHaveBeenCalled()
    await open(false); expect(api.getReferencePhonePage).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'product', productType: 'model_variant', page: 1, size: 50 }))
    expect(wrapper!.findAll('.reference-phone')).toHaveLength(2); expect(wrapper!.findAll('.reference-row')).toHaveLength(0); await chooseFirstPhone(); expect(wrapper!.findAll('.reference-group')).toHaveLength(1); expect(wrapper!.text()).toContain('0185'); expect(wrapper!.text()).toContain('亮甲 2.0')
    await wrapper!.findAll('.reference-kinds button').find(b => b.text() === '产品')!.trigger('click'); await flushPromises()
    expect(api.getReferencePage).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'product', productType: 'product_line' }))
    await wrapper!.findAll('.reference-kinds button').find(b => b.text() === 'SKU')!.trigger('click'); await flushPromises()
    expect(api.getReferencePhonePage.mock.calls.at(-1)?.[0].productType).toBeUndefined(); expect(api.getReferencePhonePage.mock.calls.at(-1)?.[0].type).toBe('sku')
  })
  it('keeps code order when combination models interrupt an otherwise shared series', async () => {
    api.getReferencePhonePage.mockResolvedValue({ content: [
      { phoneModelId: 1, brandId: 1, brandName: 'Apple', modelName: 'iPhone 17 Pro', phoneModelCode: '1300', referenceCount: 1 },
      { phoneModelId: 2, brandId: 1, brandName: 'Apple', modelName: 'iPhone 16 / 17', phoneModelCode: '1290', referenceCount: 1 },
      { phoneModelId: 3, brandId: 1, brandName: 'Apple', modelName: 'iPhone 17 Pro Max', phoneModelCode: '1280', referenceCount: 1 }
    ], totalElements: 3, brands, productLines, unlinkedCount: 0 })
    await start(); await open(false)
    expect(wrapper!.findAll('.reference-phone').map(row => row.get('b').text())).toEqual(['1300', '1290', '1280'])
  })
  it('keeps cross-brand temporary selections, restores on cancel, and confirms the original reference IDs', async () => {
    await start({ modelValue: [a] }); await open(); await brand(2); await toggle('PRODUCT-21')
    expect(chosen()).toContain('2 项'); expect(wrapper!.emitted('update:modelValue')).toBeUndefined(); await button('取消')
    await open(); expect(chosen()).toContain('1 项'); await brand(2); await toggle('PRODUCT-21'); await confirm()
    expect(wrapper!.emitted('update:modelValue')?.at(-1)).toEqual([[a, b]])
    expect(wrapper!.get('.reference-trigger').attributes('aria-expanded')).toBe('false')
  })
  it('debounces shorthand search and keeps previously selected records outside the result scope', async () => {
    await start({ modelValue: [b] }); await open(false); api.getReferencePhonePage.mockClear()
    await wrapper!.get('input[aria-label="搜索关联对象"]').setValue('17P'); await wrapper!.get('input[aria-label="搜索关联对象"]').setValue('17PM')
    expect(api.getReferencePhonePage).not.toHaveBeenCalled(); await vi.waitFor(() => expect(api.getReferencePhonePage).toHaveBeenCalledTimes(1), { timeout: 1000 })
    expect(api.getReferencePhonePage).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '17 Pro Max' })); expect(chosen()).toContain('1 项')
  })
  it('selects only the displayed page and preserves earlier pages and brands', async () => {
    catalog = Array.from({ length: 120 }, (_, i) => object(1000 + i))
    await start({ modelValue: [b] }); await open(); expect(wrapper!.findAll('.reference-row')).toHaveLength(50)
    await toggle('全选本页（50）'); expect(chosen()).toContain('51 项')
    wrapper!.getComponent(ElPagination).vm.$emit('current-change', 2); await flushPromises(); await toggle('全选本页（50）'); expect(chosen()).toContain('101 项')
    await toggle('全选本页（50）'); expect(chosen()).toContain('51 项'); await confirm()
    const result = wrapper!.emitted('update:modelValue')?.at(-1)?.[0] as ReferenceOption[]
    expect(result.map(r => r.id)).toEqual([21, ...Array.from({ length: 50 }, (_, i) => 1000 + i)])
  })
  it('retains PC and TPU scopes when existing physical references are selected again', async () => {
    const set: ReferenceOption = { ...a, type: 'mold_set', id: 300, name: '第 1 套' }
    const pc = { ...set, partType: 'PC' as const }, tpu = { ...set, partType: 'TPU' as const }
    catalog = [set]; await start({ modelValue: [pc, tpu] }); await open()
    expect(wrapper!.getComponent(ElCheckbox).props('modelValue')).toBe(true)
    await confirm(); expect(wrapper!.emitted('update:modelValue')?.at(-1)).toEqual([[pc, tpu]])
  })
  it('supports exact model and product filters and a single selected reference for list filtering', async () => {
    await start({ multiple: false }); await open(false); await wrapper!.get('[data-phone-id="100"]').trigger('click'); await flushPromises()
    expect(api.getReferencePage).toHaveBeenLastCalledWith(expect.objectContaining({ phoneModelId: 100 }))
    await wrapper!.get('select[aria-label="关联对象产品筛选"]').setValue('10'); await flushPromises()
    expect(api.getReferencePage).toHaveBeenLastCalledWith(expect.objectContaining({ productLineId: 10, page: 1 }))
    await toggle('PRODUCT-20'); await brand(2); await toggle('PRODUCT-21'); expect(chosen()).toContain('1 项'); await confirm()
    expect(wrapper!.emitted('update:modelValue')?.at(-1)).toEqual([[b]])
  })
  it('does not discard a legacy unlinked or archived reference that is absent from the result page', async () => {
    const legacy: ReferenceOption = { type: 'product', id: 777, partType: 'ALL', code: 'LEGACY', name: '历史原关联' }
    await start({ modelValue: [legacy] }); await open(); await brand(2); await confirm()
    expect(wrapper!.emitted('update:modelValue')?.at(-1)).toEqual([[legacy]])
  })
  it('keeps an API failure distinct from no results and allows retry without changing selections', async () => {
    api.getReferencePhonePage.mockRejectedValueOnce(new Error('网络不可用'))
    await start({ modelValue: [a] }); await open(false); expect(wrapper!.text()).toContain('网络不可用'); expect(wrapper!.text()).not.toContain('没有匹配的')
    await button('重试'); expect(wrapper!.findAll('.reference-phone')).toHaveLength(2); expect(chosen()).toContain('1 项')
    catalog = []; await brand(2); expect(wrapper!.text()).toContain('没有匹配的手机型号'); expect(chosen()).toContain('1 项')
  })
  it('blocks confirmation after external changes and ignores requests from a previous account', async () => {
    await start({ modelValue: [a] }); await open(); await wrapper!.setProps({ modelValue: [b] })
    expect(wrapper!.text()).toContain('外部关联已变化'); await confirm(); expect(wrapper!.emitted('update:modelValue')).toBeUndefined()
    await button('取消'); let resolve!: (value: unknown) => void
    api.getReferencePhonePage.mockReturnValue(new Promise(r => { resolve = r })); await open(false); useUserStore().token = 'different-account'; await flushPromises()
    resolve({ content: [a], totalElements: 1, brands, productLines }); await flushPromises()
    expect(wrapper!.find('.reference-panel').exists()).toBe(false); expect(wrapper!.text()).not.toContain('Apple')
  })
  it('uses the project-only query and supports Escape cancellation with trigger focus restored', async () => {
    await start({ projectOnly: true, types: ['product'], multiple: false }); await open()
    expect(api.getReferencePage).toHaveBeenLastCalledWith(expect.objectContaining({ projectOnly: true, type: 'product' }))
    await wrapper!.get('.reference-panel').trigger('keydown', { key: 'Escape' }); await flushPromises()
    expect(wrapper!.emitted('update:modelValue')).toBeUndefined(); expect(document.activeElement).toBe(wrapper!.get('.reference-trigger').element)
  })
})
