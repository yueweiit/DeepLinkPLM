import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus, { ElButton, ElCheckbox, ElPagination, ElSelect } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ catalogSuppliers: vi.fn(), catalogStyles: vi.fn(), catalogStyle: vi.fn() }))
vi.mock('@/api/modules/partnerCatalog', () => api)
vi.mock('@/views/partner/PartnerFiles.vue', () => ({ default: { props: ['files'], template: '<div>{{ files.map(file=>file.name).join(" / ") }}</div>' } }))
import PhoneIntakeSourcePicker from '../components/PhoneIntakeSourcePicker.vue'
import { useUserStore } from '@/stores/user'
const style = { supplierCode: 'SUP-A', supplierName: '供方A', name: '真实款式', sourceKind: 'selection', recordId: 3, rowId: 'real-row', rowVersion: 5, recordVersion: 0 }
const unlinked = { supplierIndex: 21, rawBrandGroup: '品牌', rawModelText: '原文4G / 5G', rawStatus: '开模中', rawNote: '原表备注', phoneModelIds: [], phones: [], matchState: 'unmatched' }
const manual = { ...unlinked, supplierIndex: 31, rawModelText: '已人工核对', phoneModelIds: [99], matchState: 'manual' }
let wrapper: VueWrapper | undefined
beforeEach(() => { vi.clearAllMocks(); setActivePinia(createPinia()); useUserStore().token = 'intake-engineer'; api.catalogSuppliers.mockResolvedValue({ content: [{ supplierCode: 'SUP-A', supplierName: '供方A' }], totalElements: 31 }); api.catalogStyles.mockResolvedValue({ content: [style], totalElements: 31 }); api.catalogStyle.mockResolvedValue({ ...style, supplierModelEntries: [unlinked, manual], modelSheetFileDetails: [{ id: 8, name: '原型号表.png' }] }) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined })
async function start(disabled = false) { wrapper = mount(PhoneIntakeSourcePicker, { props: { disabled }, global: { plugins: [ElementPlus] } }); await flushPromises() }
async function choose() { wrapper!.findAllComponents(ElSelect)[0].vm.$emit('update:modelValue', 'SUP-A'); wrapper!.findAllComponents(ElSelect)[0].vm.$emit('change', 'SUP-A'); await flushPromises(); wrapper!.findAllComponents(ElSelect)[1].vm.$emit('update:modelValue', 'selection:3:real-row'); wrapper!.findAllComponents(ElSelect)[1].vm.$emit('change'); await flushPromises() }
describe('engineering collection from an existing supplier style', () => {
  it('chooses supplier before styles, with server paging and no flat first-100 supplier fetch', async () => { await start(); expect(api.catalogSuppliers).toHaveBeenCalledWith({ q: '', page: 1, size: 20 }); expect(api.catalogStyles).not.toHaveBeenCalled(); await choose(); expect(api.catalogStyles).toHaveBeenCalledWith({ supplierCode: 'SUP-A', keyword: '', page: 1, size: 20 }); expect(api.catalogStyle).toHaveBeenCalledWith('SUP-A', expect.objectContaining({ rowId: 'real-row' })); expect(wrapper!.text()).toContain('原型号表.png'); const pager = wrapper!.findAllComponents(ElPagination)[0]; pager.vm.$emit('update:currentPage', 2); pager.vm.$emit('current-change', 2); await flushPromises(); expect(api.catalogSuppliers).toHaveBeenLastCalledWith({ q: '', page: 2, size: 20 }) })
  it('defaults to unresolved originals, can show manually linked rows, and emits only decisions with review—not a guessed missing phone', async () => { await start(); await choose(); expect(wrapper!.text()).toContain('原文4G / 5G'); expect(wrapper!.text()).not.toContain('已人工核对'); wrapper!.findAllComponents(ElCheckbox).find(value => value.text() === '查看全部原条目')!.vm.$emit('update:modelValue', true); await flushPromises(); expect(wrapper!.text()).toContain('已人工核对'); const checkbox = wrapper!.findAllComponents(ElCheckbox).find(value => value.attributes('aria-label') === '收集原序号21')!; checkbox.vm.$emit('update:modelValue', true); await flushPromises(); await wrapper!.findAllComponents(ElButton).find(value => value.text().includes('核对已选型号片段'))!.trigger('click'); expect(wrapper!.emitted('selected')?.[0]?.[0]).toEqual({ supplierCode: 'SUP-A', sourceKind: 'selection', recordId: 3, rowId: 'real-row', rowVersion: 5, recordVersion: 0, entries: [{ supplierIndex: 21, rawBrandGroup: '品牌', rawModelText: '原文4G / 5G', componentText: '原文4G / 5G', classification: 'review', reviewReason: 'name', reason: '' }] }); expect(JSON.stringify(wrapper!.emitted('selected'))).not.toContain('rawStatus') })
  it('never treats an empty source model sheet as absent supplier scope or opens a whole phone library', async () => { api.catalogStyle.mockResolvedValue({ ...style, supplierModelEntries: [] }); await start(); await choose(); expect(wrapper!.text()).toContain('先在供应商目录的本款详情核对来源型号表'); expect(wrapper!.findAllComponents(ElButton).some(value => value.text().includes('核对已选型号片段'))).toBe(false); expect(wrapper!.find('.phone-model-multi-select').exists()).toBe(false) })
  it('rejects late supplier details after a supplier switch, and disabled users cannot dispatch collection', async () => { let finish!: (value: unknown) => void; api.catalogStyle.mockReturnValue(new Promise(resolve => { finish = resolve })); await start(); await choose(); wrapper!.findAllComponents(ElSelect)[0].vm.$emit('update:modelValue', 'SUP-B'); wrapper!.findAllComponents(ElSelect)[0].vm.$emit('change', 'SUP-B'); await flushPromises(); finish({ ...style, supplierModelEntries: [unlinked] }); await flushPromises(); expect(wrapper!.text()).not.toContain('原文4G / 5G'); await wrapper!.setProps({ disabled: true }); expect(wrapper!.findAllComponents(ElSelect).every(select => select.props('disabled'))).toBe(true); expect(wrapper!.emitted('selected')).toBeUndefined() })
  it('selects repeated original indices independently across brands and within the same brand and sends precise locators',async()=>{
    const originals=[{...unlinked,supplierIndex:17,rawBrandGroup:'Apple',rawModelText:'原型号A'},
      {...unlinked,supplierIndex:17,rawBrandGroup:'Samsung',rawModelText:'原型号A'},
      {...unlinked,supplierIndex:17,rawBrandGroup:'Samsung',rawModelText:'原型号B'}]
    api.catalogStyle.mockResolvedValue({...style,supplierModelEntries:originals});await start();await choose()
    const select=async(index:number)=>{wrapper!.findAll('.source-model-list article')[index].getComponent(ElCheckbox).vm.$emit('update:modelValue',true);await flushPromises()}
    await select(1);expect(wrapper!.findAll('.source-model-list article').map(row=>!!row.getComponent(ElCheckbox).props('modelValue'))).toEqual([false,true,false])
    await select(2);expect(wrapper!.get('.source-model-tools').text()).toContain('已选 2 条')
    await wrapper!.findAllComponents(ElButton).find(value=>value.text().includes('核对已选型号片段'))!.trigger('click')
    const result=wrapper!.emitted('selected')?.[0]?.[0] as {entries:unknown[]}
    expect(result.entries).toEqual(originals.slice(1).map(entry=>({supplierIndex:17,rawBrandGroup:entry.rawBrandGroup,rawModelText:entry.rawModelText,componentText:entry.rawModelText,classification:'review',reviewReason:'name',reason:''})))
  })
  it('keeps unnumbered position locators and leading-zero indices distinct through selection and filtering',async()=>{
    const originals=['001','1','原无序号（Samsung栏111后第1行）','原无序号（Samsung栏111后第2行）'].map(supplierIndex=>({...unlinked,supplierIndex,rawBrandGroup:'Samsung',rawModelText:'原型号',rawNote:supplierIndex==='001'?'原备注：有推窗；源文件M23；原图SHA abc123':''}))
    api.catalogStyle.mockResolvedValue({...style,supplierModelEntries:originals});await start();await choose()
    expect(wrapper!.findAll('.source-model-list article>div>strong').map(label=>label.text())).toEqual(['001 · Samsung 原型号','1 · Samsung 原型号','— · Samsung 原型号','— · Samsung 原型号'])
    const notes=wrapper!.findAll('.source-model-location');expect(notes).toHaveLength(3);expect(notes[0].get('p').text()).toBe(originals[0].rawNote);expect(notes.slice(1).map(note=>note.get('p').text())).toEqual(originals.slice(2).map(entry=>entry.supplierIndex));expect(notes.every(note=>!(note.element as HTMLDetailsElement).open)).toBe(true)
    for(const row of wrapper!.findAll('.source-model-list article'))row.getComponent(ElCheckbox).vm.$emit('update:modelValue',true)
    await flushPromises();expect(wrapper!.get('.source-model-tools').text()).toContain('已选 4 条')
    await wrapper!.get('[aria-label="来源型号原序号搜索"]').setValue('第2行');expect(wrapper!.findAll('.source-model-list article')).toHaveLength(1)
    await wrapper!.findAllComponents(ElButton).find(value=>value.text().includes('核对已选型号片段'))!.trigger('click')
    const result=wrapper!.emitted('selected')?.[0]?.[0] as {entries:{supplierIndex:string}[]}
    expect(result.entries.map(entry=>entry.supplierIndex)).toEqual(originals.map(entry=>entry.supplierIndex))
  })

})
