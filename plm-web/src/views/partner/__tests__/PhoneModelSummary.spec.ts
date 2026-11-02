import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElInput, ElPagination } from 'element-plus'
import type { CompatiblePhone } from '@/types/molds'
import PhoneModelSummary from '../PhoneModelSummary.vue'
import { catalogPhoneGroups, catalogPhoneSummary } from '../catalog/catalogPresentation'

let wrapper: VueWrapper
const phone = (id: number, modelName: string, brandName = 'APPLE'): CompatiblePhone => ({ id, phoneModelId: id, brandId: brandName === 'APPLE' ? 1 : 2, brandName, modelName, aliases: [], codeItemId: id, phoneModelCode: String(id).padStart(4, '0'), codeLinkStatus: 'valid', status: 'enabled' })
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })
function start(phones: CompatiblePhone[], count = phones.length) {
  wrapper = mount(PhoneModelSummary, { props: { phones, count, fallback: catalogPhoneSummary({ phones }), originalText: '原始型号说明' }, global: { plugins: [ElementPlus] } })
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
    expect(sections[1].textContent).toContain('组合型号')
    expect(sections[1].textContent).toContain('12/13 Pro')
    expect(sections[2].textContent).toContain('SM Galaxy S26 Ultra')
    expect(sections[2].textContent).toContain('0004')
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
})
