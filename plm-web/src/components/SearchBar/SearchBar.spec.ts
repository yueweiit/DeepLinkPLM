import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import type { SearchField } from '@/types/common'
import SearchBar from './index.vue'

const fields: SearchField[] = [
  { prop: 'keyword', label: '关键词', type: 'input', placeholder: '搜索名称' },
  {
    prop: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '全部', value: '' },
      { label: '进行中', value: 'active' }
    ]
  },
  {
    prop: 'createdAt',
    label: '创建日期',
    type: 'date'
  }
]

function mountSearchBar(modelValue: Record<string, unknown> = {}) {
  return mount(SearchBar, {
    props: { fields, modelValue },
    global: { plugins: [ElementPlus] }
  })
}

describe('SearchBar mobile filters', () => {
  it('keeps additional fields collapsed until the filter control is opened', async () => {
    const wrapper = mountSearchBar()

    expect(wrapper.find('.search-form--desktop').exists()).toBe(true)
    expect(wrapper.find('.search-form--mobile').exists()).toBe(true)
    expect(wrapper.find('.search-form__mobile-advanced').exists()).toBe(false)
    expect(wrapper.get('.search-form__mobile-filter').attributes('aria-expanded')).toBe('false')

    await wrapper.get('.search-form__mobile-filter').trigger('click')
    await flushPromises()

    expect(wrapper.get('.search-form__mobile-advanced').findAll('.el-form-item')).toHaveLength(2)
    expect(wrapper.get('.search-form__mobile-filter').attributes('aria-expanded')).toBe('true')
    wrapper.unmount()
  })

  it('shows active filter count and applies or resets the complete query', async () => {
    const wrapper = mountSearchBar({ keyword: '手机壳', status: 'active', createdAt: '2026-11-18' })

    expect(wrapper.get('.search-form__filter-count').text()).toBe('2')
    await wrapper.get('.search-form__mobile-search').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('search')?.[0]?.[0]).toEqual({ keyword: '手机壳', status: 'active', createdAt: '2026-11-18' })
    await wrapper.get('.search-form__mobile-filter').trigger('click')
    await wrapper.get('.search-form__mobile-advanced-actions').findAll('button')[0].trigger('click')
    await flushPromises()

    expect(wrapper.emitted('reset')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({ keyword: '', status: '', createdAt: '' })
    expect(wrapper.find('.search-form__mobile-advanced').exists()).toBe(false)
    wrapper.unmount()
  })
})
