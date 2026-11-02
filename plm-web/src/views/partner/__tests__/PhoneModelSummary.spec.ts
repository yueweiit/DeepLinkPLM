import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElInput, ElPagination } from 'element-plus'
import type { CompatiblePhone } from '@/types/molds'
import PhoneModelSummary from '../PhoneModelSummary.vue'
import { catalogDisplayPhones, catalogPhoneGroups, catalogPhoneSummary, type SummaryPhone } from '../catalog/catalogPresentation'

let wrapper: VueWrapper
const phone = (id: number, modelName: string, brandName = 'APPLE'): CompatiblePhone => ({ id, phoneModelId: id, brandId: (({ APPLE: 1, SAMSUNG: 2, XIAOMI: 3, HONOR: 4, HUAWEI: 5 } as Record<string, number>)[brandName] || 6), brandName, modelName, aliases: [], codeItemId: id, phoneModelCode: String(id).padStart(4, '0'), codeLinkStatus: 'valid', status: 'enabled' })
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })
function start(phones: SummaryPhone[], count = phones.length, scope: 'selection' | 'supply' | 'listing' = 'selection') {
  wrapper = mount(PhoneModelSummary, { props: { phones, count, scope, fallback: catalogPhoneSummary({ phones }), originalText: '原始型号说明' }, global: { plugins: [ElementPlus] } })
}
describe('selected phone summary is a read-only view of the actual members', () => {
  it('shows the selected generations and count rather than the first iPhone for a large range', () => {
    const phones = Array.from({ length: 39 }, (_, index) => phone(index + 1, `Apple iPhone ${11 + index % 8}${index % 2 ? ' Pro' : ''}`))
    start(phones)
    expect(wrapper.get('.model-title').text()).toBe('iPhone 11—18 系列')
    expect(wrapper.get('.phone-family').text()).not.toContain('已选 39 个型号')
    expect(wrapper.get('.all-models').text()).toBe('共 39 个型号 · 查看明细')
    expect(wrapper.text()).not.toContain('全系列已选')
  })
  it('keeps each selected brand visible instead of hiding Samsung behind the first Apple', () => {
    const phones = [phone(1, 'Apple iPhone 18 Pro'), phone(2, 'Samsung Galaxy S26 Ultra', 'SAMSUNG'), phone(3, 'Xiaomi 17', 'XIAOMI')]
    start(phones)
    expect(wrapper.findAll('.model-title').map(item => item.text())).toEqual(['iPhone 18 系列', 'Samsung Galaxy S26 系列', 'XIAOMI'])
    expect(wrapper.findAll('.phone-family').map(item => item.text())).toEqual(expect.arrayContaining([expect.stringContaining('iPhone'), expect.stringContaining('Samsung'), expect.stringContaining('XIAOMI')]))
  })
  it('does not fill generation gaps or infer mini exclusions from a sparse selection', () => {
    const phones = [phone(1, 'Apple iPhone 11'), phone(2, 'Apple iPhone 18 Pro'), phone(3, 'Apple iPhone 18 Pro Max')]
    expect(catalogPhoneGroups(phones)[0].label).toBe('iPhone 11、18 系列')
    expect(catalogPhoneSummary({ phones })).not.toContain('11—18')
    expect(catalogPhoneSummary({ phones })).not.toContain('mini除外')
  })
  it('preserves the exact names when only one or two phones were chosen', () => {
    const phones = [phone(1, 'Apple iPhone 18 Pro'), phone(2, 'Apple iPhone 18 Pro Max')]
    start(phones)
    expect(wrapper.get('.model-title').text()).toBe('Apple iPhone 18 Pro、Apple iPhone 18 Pro Max')
    expect(wrapper.find('.all-models').exists()).toBe(false)
  })
  it('shows the real selected count when some identity details have not loaded', () => {
    start([phone(1, 'Apple iPhone 11')], 39)
    expect(wrapper.get('.model-title').text()).toBe('已选 39 个型号')
    expect(wrapper.find('.phone-family').exists()).toBe(false)
  })
  it('shows each generation in a separate section and searches only the actual selection without editing it', async () => {
    const phones = [...Array.from({ length: 22 }, (_, index) => phone(index + 1, `Apple iPhone ${11 + index % 8} Pro`)), phone(99, 'Samsung Galaxy S26 Ultra', 'SAMSUNG')], original = JSON.stringify(phones)
    start(phones)
    await wrapper.get('.all-models').trigger('click'); await flushPromises()
    expect(document.querySelectorAll('.selected-phone-member')).toHaveLength(23)
    expect(document.querySelectorAll('.selected-phone-group')).toHaveLength(9)
    expect(document.querySelectorAll('.selected-phone-grid')).toHaveLength(9)
    expect(wrapper.findComponent(ElPagination).exists()).toBe(false)
    await wrapper.getComponent(ElInput).setValue('S26'); await flushPromises()
    expect(document.querySelectorAll('.selected-phone-member')).toHaveLength(1)
    expect(document.querySelector('.selected-phone-member')?.textContent).toContain('Samsung Galaxy S26 Ultra')
    expect(document.querySelector('.selected-phone-details')?.textContent).not.toContain('保存')
    expect(JSON.stringify(phones)).toBe(original)
    expect(wrapper.emitted()).not.toHaveProperty('update:phones')
    expect(wrapper.emitted()).not.toHaveProperty('update:count')
  })
  it('groups old IP and SM abbreviations with the same generation while retaining combined names and every original code', async () => {
    const phones = [phone(1, 'IP 12 Pro'), phone(2, 'Apple iPhone 12'), phone(3, 'Apple iPhone 12/13 Pro'), phone(4, 'SM Galaxy S26 Ultra', 'SAMSUNG'), phone(5, 'Samsung Galaxy S26', 'SAMSUNG')]
    start(phones); await wrapper.get('.all-models').trigger('click'); await flushPromises()
    const sections = [...document.querySelectorAll('.selected-phone-group')]
    expect(sections).toHaveLength(3)
    expect(sections[0].textContent).toContain('IP 12 Pro')
    expect(sections[0].textContent).toContain('Apple iPhone 12')
    const combinations = document.querySelector<HTMLDetailsElement>('.phone-combinations')!
    expect(combinations.open).toBe(false)
    expect(combinations.textContent).toContain('12/13 Pro')
    expect(sections[1].textContent).toContain('SM Galaxy S26 Ultra')
    expect(sections[1].textContent).toContain('0004')
    expect(document.querySelectorAll('.selected-phone-member')).toHaveLength(5)
    document.querySelectorAll<HTMLButtonElement>('.phone-brand-filters .el-button')[2].click(); await flushPromises()
    expect(document.querySelectorAll('.selected-phone-member')).toHaveLength(2)
    expect(document.querySelector('.selected-phone-group')?.textContent).not.toContain('iPhone')
  })
  it('pages by whole generation groups and resets the page when filtering', async () => {
    start(Array.from({ length: 15 }, (_, i) => phone(i + 1, `Samsung Galaxy S${10 + i}`, 'SAMSUNG')))
    await wrapper.get('.all-models').trigger('click'); await flushPromises()
    expect(document.querySelectorAll('.selected-phone-group')).toHaveLength(12)
    expect(wrapper.getComponent(ElPagination).props('total')).toBe(15)
    await wrapper.getComponent(ElPagination).vm.$emit('update:current-page', 2); await flushPromises()
    expect(document.querySelectorAll('.selected-phone-group')).toHaveLength(3)
    await wrapper.getComponent(ElInput).setValue('S24'); await flushPromises()
    expect(document.querySelectorAll('.selected-phone-member')).toHaveLength(1)
  })
  it('collapses explicit same-brand/code aliases without changing stored members', () => {
    const phones = [
      { ...phone(301, 'IP 14 Pro Max'), phoneModelCode: '0743' },
      { ...phone(302, 'Apple iPhone 14 Pro Max'), phoneModelCode: '0743' },
      { ...phone(303, 'IP12/12 Pro'), phoneModelCode: '0057' },
      { ...phone(304, 'Apple iPhone 12/iPhone 12 Pro'), phoneModelCode: '0057' },
    ], original = JSON.stringify(phones)
    expect(catalogDisplayPhones(phones).map(item => item.phoneModelId)).toEqual([301, 303])
    expect(catalogPhoneGroups(phones).flatMap(group => group.phones)).toHaveLength(2)
    expect(JSON.stringify(phones)).toBe(original)
    expect(phones.map(item => item.phoneModelId)).toEqual([301, 302, 303, 304])
  })
  it.each([
    ['different brands', { ...phone(1, 'X5', 'HONOR'), phoneModelCode: '0743' }, { ...phone(2, 'X5', 'HUAWEI'), phoneModelCode: '0743' }],
    ['4G and 5G', { ...phone(1, 'Samsung A15 4G', 'SAMSUNG'), phoneModelCode: '0743' }, { ...phone(2, 'Samsung A15 5G', 'SAMSUNG'), phoneModelCode: '0743' }],
    ['large and fine holes', { ...phone(1, 'Apple iPhone 16 Pro Gran agujero'), phoneModelCode: '0743' }, { ...phone(2, 'Apple iPhone 16 Pro Agujero fino'), phoneModelCode: '0743' }],
    ['individual and combined models', { ...phone(1, 'Apple iPhone 12'), phoneModelCode: '0057' }, { ...phone(2, 'Apple iPhone 12/12 Pro'), phoneModelCode: '0057' }],
    ['leading zero code differences', { ...phone(1, 'Apple iPhone 12'), phoneModelCode: '0057' }, { ...phone(2, 'Apple iPhone 12'), phoneModelCode: '57' }],
    ['unassigned identities', { ...phone(1, 'Apple iPhone 12'), phoneModelCode: null }, { ...phone(2, 'Apple iPhone 12'), phoneModelCode: null }],
  ] as const)('keeps %s distinct', (_label, first, second) => {
    expect(catalogDisplayPhones([first, second]).map(item => item.phoneModelId)).toEqual([1, 2])
  })
  it('collapses a repeated member ID before grouping it', () => {
    const original = phone(1, 'Apple iPhone 14 Pro Max')
    expect(catalogDisplayPhones([original, { ...original }])).toEqual([original])
  })
  it('collapses the verified 0743 and 0057 records while preserving their four saved IDs', () => {
    const phones = [
      { ...phone(182, 'Apple iPhone 14 Pro Max'), phoneModelCode: '0743' },
      { ...phone(599, 'Apple iPhone 14 ProMax'), phoneModelCode: '0743' },
      { ...phone(564, 'Apple iPhone 12/12 Pro'), phoneModelCode: '0057' },
      { ...phone(647, 'Apple iPhone 12/iPhone 12 Pro'), phoneModelCode: '0057' },
    ], original = JSON.stringify(phones)
    expect(catalogDisplayPhones(phones).map(item => item.phoneModelId)).toEqual([182, 564])
    expect(JSON.stringify(phones)).toBe(original)
  })
  it('reads legacy listed models without inventing member or brand IDs', async () => {
    const phones = [{ modelName: '旧款手机A', phoneModelCode: '0012' }, { modelName: '旧款手机B', phoneModelCode: '0013' }], original = JSON.stringify(phones)
    start(phones, 2, 'listing')
    await wrapper.get('.all-models').trigger('click'); await flushPromises()
    expect(document.querySelector('.phone-count')?.textContent).toContain('经营 2 个型号')
    expect(document.querySelectorAll('.selected-phone-member')).toHaveLength(2)
    await wrapper.getComponent(ElInput).setValue('0013'); await flushPromises()
    expect(document.querySelectorAll('.selected-phone-member')).toHaveLength(1)
    expect(document.querySelector('.selected-phone-member')?.textContent).toContain('旧款手机B')
    expect(JSON.stringify(phones)).toBe(original)
    expect(wrapper.emitted()).not.toHaveProperty('update:phones')
  })
  it('counts individual phones once and keeps combinations collapsed and searchable', async () => {
    const phones = [
      { ...phone(301, 'Apple iPhone 14 Pro Max'), phoneModelCode: '0743' },
      { ...phone(302, 'IP 14 Pro Max'), phoneModelCode: '0743' },
      { ...phone(303, 'Apple iPhone 12/12 Pro'), phoneModelCode: '0057' },
      { ...phone(304, 'Apple iPhone 12/iPhone 12 Pro'), phoneModelCode: '0057' },
      { ...phone(305, 'Apple iPhone 12'), phoneModelCode: '0608' },
      { ...phone(306, 'Apple iPhone 12 Pro'), phoneModelCode: '0609' },
    ], original = JSON.stringify(phones)
    start(phones); await wrapper.get('.all-models').trigger('click'); await flushPromises()
    expect(document.querySelector('.phone-count')?.textContent).toContain('3 个手机型号 · 1 组组合适配')
    expect(document.querySelector<HTMLDetailsElement>('.phone-combinations')?.open).toBe(false)
    const regular = [...document.querySelectorAll('.selected-phone-member')].filter(item => !item.closest('.phone-combinations'))
    expect(regular).toHaveLength(3)
    expect(regular.filter(item => item.textContent?.includes('0743'))).toHaveLength(1)
    expect(document.querySelectorAll('.phone-combinations .selected-phone-member')).toHaveLength(1)
    await wrapper.getComponent(ElInput).setValue('iPhone 12/iPhone 12 Pro'); await flushPromises()
    expect(document.querySelector<HTMLDetailsElement>('.phone-combinations')?.open).toBe(true)
    expect(document.querySelectorAll('.selected-phone-member')).toHaveLength(1)
    expect(document.querySelector('.selected-phone-member')?.textContent).toContain('0057')
    expect(JSON.stringify(phones)).toBe(original)
    expect(wrapper.emitted()).not.toHaveProperty('update:phones')
  })
  it.each([['supply', '供应商可供手机型号'], ['listing', '经营手机型号']] as const)('opens a read-only %s detail even for one model', async (scope, title) => {
    start([{ ...phone(1, 'Apple iPhone 14 Pro'), productionStatus: 'discontinued' }], 1, scope)
    await wrapper.get('.all-models').trigger('click'); await flushPromises()
    expect(document.querySelector('.selected-phone-details')?.textContent).toContain(title)
    expect(document.querySelector('.selected-phone-details')?.textContent).not.toContain('保存')
    expect(document.querySelectorAll('.selected-phone-member')).toHaveLength(1)
    expect(document.querySelector('.selected-phone-member')?.textContent).toContain('已停产')
    expect(wrapper.emitted()).not.toHaveProperty('update:phones')
  })

})
