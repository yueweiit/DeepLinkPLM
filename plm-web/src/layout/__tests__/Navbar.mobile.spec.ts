import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ref } from 'vue'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Navbar from '../Navbar.vue'
import { useUserStore } from '@/stores/user'

vi.mock('@vueuse/core', () => ({ useMediaQuery: () => ref(true) }))
vi.mock('@/composables/useClientUpdate', () => ({ useClientUpdate: () => ({ available: { value: false }, refresh: vi.fn() }) }))

let wrapper: VueWrapper

afterEach(() => wrapper?.unmount())

async function start(path: string, permissions: string[] = ['partner:view']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const user = useUserStore()
  user.token = 'mobile-navbar-test'
  user.permissions = permissions
  user.profile = { userId: 1, userName: '手机测试', roleName: '测试角色', department: '测试部门' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/mobile', component: { template: '<div />' } },
      { path: '/partner-products', component: { template: '<div />' } },
      { path: '/partner-selections', component: { template: '<div />' } },
      { path: '/product-research', component: { template: '<div />' } },
      { path: '/projects', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } }
    ]
  })
  await router.push(path)
  await router.isReady()
  wrapper = mount(Navbar, { global: { plugins: [pinia, router, ElementPlus] } })
  await flushPromises()
  return router
}

describe('mobile navbar navigation', () => {
  it('returns from a module to the directory it came from', async () => {
    const router = await start('/partner-selections?mobileFrom=%2Fmobile%3Fview%3Dmore')
    const back = wrapper.get('.navbar__mobile-back')
    await back.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/mobile')
    expect(router.currentRoute.value.query).toEqual({ view: 'more' })
  })

  it('returns a non-root detail route to its bottom navigation root', async () => {
    const router = await start('/partner-products?supplierCode=SUP-1')
    const back = wrapper.get('.navbar__mobile-back')
    await back.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/partner-products')
    expect(router.currentRoute.value.query).toEqual({})
  })

  it('returns a supplier style detail to the selected supplier list first', async () => {
    const router = await start('/partner-products?supplierCode=SUP-1&supplierPage=2&sourceKind=quote&recordId=17&rowId=CP-000140')
    await wrapper.get('.navbar__mobile-back').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/partner-products')
    expect(router.currentRoute.value.query).toEqual({ supplierCode: 'SUP-1', supplierPage: '2' })
  })

  it('hides back on the home, profile, and bottom navigation root pages', async () => {
    await start('/mobile')
    expect(wrapper.find('.navbar__mobile-back').exists()).toBe(false)

    await start('/mobile?view=profile')
    expect(wrapper.find('.navbar__mobile-back').exists()).toBe(false)

    await start('/partner-products')
    expect(wrapper.find('.navbar__mobile-back').exists()).toBe(false)
  })

  it('shows a plain arrow button for the directory view', async () => {
    await start('/mobile?view=more')
    const back = wrapper.get('.navbar__mobile-back')
    expect(back.attributes('circle')).toBeUndefined()
    expect(back.classes()).not.toContain('is-circle')
  })

  it('keeps language and logout actions inside the personal center only', async () => {
    await start('/partner-products')
    expect(wrapper.find('.navbar__mobile-actions').exists()).toBe(false)
    expect(wrapper.find('select.language-switcher').exists()).toBe(false)
    expect(wrapper.find('.navbar__logout').exists()).toBe(false)
  })
})
