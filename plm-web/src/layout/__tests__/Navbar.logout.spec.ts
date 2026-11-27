import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, disposePinia, setActivePinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { ref } from 'vue'
import ElementPlus, { ElMessage } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Navbar from '../Navbar.vue'
import { useUserStore } from '@/stores/user'

const auth = vi.hoisted(() => ({ logout: vi.fn() }))
vi.mock('@/api/modules/auth', () => ({
  getProfileByToken: vi.fn(),
  loginByPassword: vi.fn(),
  loginByDingTalkCode: vi.fn(),
  logout: auth.logout,
  saveOwnLanguage: vi.fn()
}))
vi.mock('@vueuse/core', () => ({ useMediaQuery: () => ref(false) }))
vi.mock('@/composables/useClientUpdate', () => ({ useClientUpdate: () => ({ available: { value: false }, refresh: vi.fn() }) }))
vi.mock('@/utils/confirmLogout', () => ({ confirmLogout: async (onConfirmed: () => Promise<void>) => onConfirmed() }))

let pinia: Pinia, router: Router, wrapper: VueWrapper

beforeEach(async () => {
  vi.clearAllMocks()
  localStorage.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  const user = useUserStore()
  user.token = 'navbar-logout-token'
  user.profile = { userId: 1, userName: '测试账号', roleName: '管理员', department: '工程' }
  user.permissions = ['dashboard:view']
  localStorage.setItem('plm_token', user.token)
  auth.logout.mockResolvedValue(undefined)
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/dashboard', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } }
    ]
  })
  await router.push('/dashboard')
  await router.isReady()
  wrapper = mount(Navbar, { global: { plugins: [pinia, router, ElementPlus] } })
})

afterEach(() => {
  wrapper?.unmount()
  router?.options.history.destroy()
  disposePinia(pinia)
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('desktop logout feedback', () => {
  it('shows success after clearing the local session and navigating to login', async () => {
    const success = vi.spyOn(ElMessage, 'success').mockReturnValue({ close: vi.fn() } as never)

    await wrapper.get('.navbar__logout').trigger('click')
    await flushPromises()

    expect(auth.logout).toHaveBeenCalledWith('navbar-logout-token')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(localStorage.getItem('plm_token')).toBeNull()
    expect(success).toHaveBeenCalledWith('退出成功')
  })

  it('warns only when the server could not confirm logout', async () => {
    auth.logout.mockRejectedValue(new Error('Network Error'))
    const warning = vi.spyOn(ElMessage, 'warning').mockReturnValue({ close: vi.fn() } as never)

    await wrapper.get('.navbar__logout').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/login')
    expect(localStorage.getItem('plm_token')).toBeNull()
    expect(warning).toHaveBeenCalledWith('本机已退出，服务端注销未确认')
  })
})
