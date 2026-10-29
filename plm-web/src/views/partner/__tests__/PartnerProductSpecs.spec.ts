import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElPagination, ElSelect } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PartnerProductSpecs from '../PartnerProductSpecs.vue'
import type { BusinessData } from '@/types/partner'

const specs = (count = 164): BusinessData[] => Array.from({ length: count }, (_, index) => ({
  id: index + 101,
  name: `来源原型号 ${index + 1} / 4G、5G · 白色`,
  code: `PS-original-${index + 1}`,
  websiteSku: `EXTERNAL-${index + 1}`,
  phones: [{ brandName: 'MOTOROLA', modelName: `Moto ${index + 1}`, phoneModelCode: index === 163 ? '0007' : '1294' }],
  sources: index === 163 ? [{ supplierCode: 'TIM', supplierName: 'Tim 配件', supplierItemCode: '供方原货号-9', priceCents: 1250, currency: 'MXN' }] : []
}))
let wrapper: VueWrapper
function start(rows = specs(), props: Record<string, unknown> = {}) {
  wrapper = mount(PartnerProductSpecs, { props: { productId: 7, specs: rows, canWrite: true, canReadMoney: false, formatMoney: (value, currency) => `${currency} ${Number(value) / 100}`, ...props }, global: { plugins: [ElementPlus] } })
  return wrapper
}
async function page(number: number) {
  wrapper.getComponent(ElPagination).vm.$emit('update:current-page', number)
  await flushPromises()
}
async function search(value: string) {
  await wrapper.get('input[aria-label="搜索产品规格"]').setValue(value)
  await flushPromises()
}
const visibleIds = () => wrapper.findAll('.spec-row').map(row => Number(row.attributes('data-spec-id')))
afterEach(() => wrapper?.unmount())

describe('partner product specification browsing', () => {
  it('renders only the current 20 rows and reaches every original ID once across all 9 pages', async () => {
    const original = specs(), snapshot = JSON.stringify(original)
    start(original)
    expect(visibleIds()).toEqual(original.slice(0, 20).map(row => row.id))
    const visited: number[] = []
    for (let number = 1; number <= 9; number++) { await page(number); visited.push(...visibleIds()) }
    expect(visited).toEqual(original.map(row => row.id))
    expect(wrapper.get('.spec-pager').text()).toContain('161–164 / 164')
    expect(JSON.stringify(original)).toBe(snapshot)
  })

  it('searches a last-page website SKU immediately and resets paging, then clearing restores all rows', async () => {
    start(); await page(8); await search('external-164')
    expect(visibleIds()).toEqual([264])
    expect(wrapper.get('.spec-pager').text()).toContain('第 1 / 1 页')
    expect(wrapper.text()).toContain('匹配 1 个')
    await search('')
    expect(visibleIds()).toHaveLength(20)
    expect(wrapper.getComponent(ElPagination).props('total')).toBe(164)
  })

  it('searches original phone codes with leading zeros, brands, model names and supplier references without recoding', async () => {
    start()
    for (const query of ['0007', '  EXTERNAL-164  ', 'Moto 164', 'Tim 配件', '供方原货号-9', 'PS-original-164']) {
      await search(query)
      expect(visibleIds()).toEqual([264])
    }
    await search('motorola')
    expect(wrapper.getComponent(ElPagination).props('total')).toBe(164)
    await search('4G、5G')
    expect(wrapper.getComponent(ElPagination).props('total')).toBe(164)
  })

  it('resets on page size changes and clamps the page when the refreshed list becomes shorter', async () => {
    start(); await page(9)
    wrapper.getComponent(ElSelect).vm.$emit('update:modelValue', 50)
    await flushPromises()
    expect(visibleIds()).toHaveLength(50)
    expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(1)
    await page(4)
    await wrapper.setProps({ specs: specs(21) })
    expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(1)
    expect(visibleIds()).toHaveLength(21)
  })

  it('clears another product’s search and page when the detail object changes', async () => {
    start(); await search('motorola'); await page(3)
    await wrapper.setProps({ productId: 9, specs: specs(24) })
    await flushPromises()
    expect((wrapper.get('input[aria-label="搜索产品规格"]').element as HTMLInputElement).value).toBe('')
    expect(visibleIds()[0]).toBe(101)
    expect(wrapper.getComponent(ElPagination).props('currentPage')).toBe(1)
  })

  it('shows a recoverable no-match state and a distinct empty-product state', async () => {
    start(); await search('没有这个规格')
    expect(visibleIds()).toEqual([])
    expect(wrapper.text()).toContain('没有匹配的规格')
    expect(wrapper.get('.spec-pager').text()).toContain('0 / 0 个规格')
    await wrapper.findAll('button').find(button => button.text() === '清除搜索')!.trigger('click')
    expect(visibleIds()).toHaveLength(20)
    await wrapper.setProps({ specs: [] })
    expect(wrapper.text()).toContain('可先保存名称，需要时再补规格与供方')
  })

  it('keeps legacy handwritten names and incomplete source fields usable without inventing a phone or SKU', () => {
    start([{ id: 19, name: '历史组合机型 / 蓝色', sources: [] }])
    expect(wrapper.text()).toContain('历史组合机型 / 蓝色')
    expect(wrapper.text()).toContain('规格编号未记录')
    expect(wrapper.text()).toContain('网站 SKU：待补')
    expect(wrapper.text()).toContain('待补供应商')
    expect(wrapper.get('.spec-actions').text()).toContain('删除规格')
  })

  it.each([
    { canWrite: false, canReadMoney: false },
    { canWrite: false, canReadMoney: true },
    { canWrite: true, canReadMoney: false },
    { canWrite: true, canReadMoney: true }
  ])('preserves independent operation and amount permissions: %o', async permissions => {
    const formatMoney = vi.fn(() => 'MXN 12.50')
    start(specs().slice(-1), { ...permissions, formatMoney })
    expect(wrapper.text().includes('MXN 12.50')).toBe(permissions.canReadMoney)
    expect(formatMoney.mock.calls.length > 0).toBe(permissions.canReadMoney)
    expect(wrapper.find('.spec-actions').exists()).toBe(permissions.canWrite)
    expect(wrapper.text().includes('登记收样')).toBe(permissions.canWrite)
    expect(wrapper.text().includes('供方询报价')).toBe(permissions.canWrite)
  })

  it('emits the unchanged source spec and supplier code after searching, never an index or reconstructed ID', async () => {
    const original = specs()
    start(original); await page(7); await search('0007')
    for (const text of ['Tim 配件', '登记收样', '供方询报价']) await wrapper.findAll('button').find(button => button.text() === text)!.trigger('click')
    expect(wrapper.emitted('supplier')).toEqual([['TIM']])
    expect(wrapper.emitted('sample')?.[0][0]).toEqual(original[163])
    expect(wrapper.emitted('quote')?.[0][0]).toEqual(original[163])
    expect(wrapper.emitted('sample')?.[0][0]).toBe(wrapper.getComponent(PartnerProductSpecs).props('specs')[163])
  })
})
