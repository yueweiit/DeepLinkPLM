import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { useUserStore } from '@/stores/user'
import MobileBottomNavigation from '../MobileBottomNavigation.vue'

async function mountNavigation(permissions: string[], path: string, roleCode?: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const user = useUserStore()
  user.token = 'mobile-navigation-test'
  user.permissions = permissions
  user.profile = { userId: 1, userName: '手机测试', roleName: '测试角色', department: '测试部门', roleCode }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/mobile', component: { template: '<div />' } },
      { path: '/projects', component: { template: '<div />' } },
      { path: '/product-research', component: { template: '<div />' } },
      { path: '/pilot/tasks', component: { template: '<div />' } },
      { path: '/approval-tasks', component: { template: '<div />' } },
      { path: '/partner-products', component: { template: '<div />' } },
      { path: '/samples', component: { template: '<div />' } }
    ]
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(MobileBottomNavigation, { global: { plugins: [ElementPlus, pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('MobileBottomNavigation', () => {
  it('sends a supplier task tab to phone tasks and returns to the section root', async () => {
    const { wrapper, router } = await mountNavigation(
      ['pilot:tasks', 'partner:view'],
      '/approval-tasks?view=tasks',
      'supplier'
    )
    await wrapper.findAll('button').find(button => button.text() === '待办')!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/pilot/tasks')
  })

  it('returns from research detail routes to the project center', async () => {
    const { wrapper, router } = await mountNavigation(['project:view'], '/product-research')
    await wrapper.findAll('button').find(button => button.text() === '研发')!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/projects')
  })

  it('returns from the more directory to the mobile home when the home tab is tapped', async () => {
    const { wrapper, router } = await mountNavigation([], '/mobile?view=more')
    await wrapper.findAll('button').find(button => button.text() === '首页')!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/mobile')
    expect(router.currentRoute.value.query).toEqual({})
    expect(wrapper.findAll('button').map(button => button.text())).not.toContain('业务')
  })

  it('opens the personal center from the profile tab', async () => {
    const { wrapper, router } = await mountNavigation([], '/mobile')
    await wrapper.findAll('button').find(button => button.text() === '我的')!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/mobile')
    expect(router.currentRoute.value.query).toEqual({ view: 'profile' })
  })

  it('shows only destinations available to the signed-in role', async () => {
    const { wrapper } = await mountNavigation(['task:arrival'], '/samples', 'mexico')
    expect(wrapper.findAll('button').map(button => button.text())).toEqual(['首页', '样品', '我的'])
  })
})
