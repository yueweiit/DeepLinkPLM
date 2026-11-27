import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const orderApi = vi.hoisted(() => ({ getOrders: vi.fn() }))
vi.mock('@/api/modules/order', () => orderApi)

import OrderCenterView from '../OrderCenterView.vue'

describe('OrderCenterView', () => {
  beforeEach(() => {
    orderApi.getOrders.mockResolvedValue({ content: [
      { orderId: 1, dingTalkApprovalNo: 'DT-001', orderCode: 'ORD-001', phoneModel: 'iPhone 18', projectType: 'model_variant', orderType: 'customer_requirement', productName: '超队 3.0', status: 'confirmed', createdAt: '2026-07-20T10:00:00' },
      { orderId: 2, dingTalkApprovalNo: 'DT-002', orderCode: 'ORD-002', phoneModel: null, projectType: 'product_line', orderType: 'market_requirement', productName: '新产品', status: 'completed', createdAt: '2026-07-20T11:00:00' }
    ], page: 1, size: 20, totalElements: 2, totalPages: 1 })
  })
  it('loads real orders and renders required column order', async () => {
    const wrapper = mount(OrderCenterView, { global: { plugins: [ElementPlus] } }); await flushPromises()
    expect(orderApi.getOrders).toHaveBeenCalled()
    const text = wrapper.text()
    expect(text.indexOf('钉钉审批编号')).toBeLessThan(text.indexOf('订单号'))
    expect(text.indexOf('订单号')).toBeLessThan(text.indexOf('手机型号'))
    expect(text).toContain('DT-001'); expect(text).toContain('iPhone 18'); expect(text).toContain('--')
  })

  it('uses readable order cards at phone widths', async () => {
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn()
    }))
    const wrapper = mount(OrderCenterView, { global: { plugins: [ElementPlus] } }); await flushPromises()
    expect(wrapper.findAll('.order-card')).toHaveLength(2)
    expect(wrapper.text()).toContain('DT-001')
    expect(wrapper.text()).toContain('iPhone 18')
    expect(wrapper.find('.el-table').exists()).toBe(false)
    vi.unstubAllGlobals()
  })
})
