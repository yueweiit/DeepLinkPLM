import ElementPlus, { ElMessage } from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import * as auth from '@/api/modules/auth'
import { useUserStore } from '@/stores/user'
import { setLanguage } from '@/locales/language'
import MobileProfileView from '../MobileProfileView.vue'

vi.mock('@/api/modules/auth', async importOriginal => {
  const actual = await importOriginal<typeof import('@/api/modules/auth')>()
  return {
    ...actual,
    changeOwnPassword: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined)
  }
})
vi.mock('@/utils/confirmLogout', () => ({ confirmLogout: async (onConfirmed: () => Promise<void>) => onConfirmed() }))

async function mountProfile() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const user = useUserStore()
  user.token = 'mobile-profile-test'
  user.permissions = ['*']
  user.profile = { userId: 9, userName: '手机端测试账号', roleName: '测试角色', department: '测试部门' }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/mobile', component: MobileProfileView },
      { path: '/login', component: { template: '<div>登录</div>' } }
    ]
  })
  await router.push('/mobile')
  await router.isReady()
  const wrapper = mount({ template: '<router-view />' }, { global: {
    plugins: [ElementPlus, pinia, router],
    stubs: {
      ElDialog: {
        props: ['modelValue'],
        template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>'
      }
    }
  } })
  await flushPromises()
  return { wrapper, router, user }
}

describe('MobileProfileView', () => {
  afterEach(() => vi.restoreAllMocks())

  beforeEach(() => {
    localStorage.clear()
    setLanguage('zh-CN')
    vi.clearAllMocks()
    vi.mocked(auth.changeOwnPassword).mockResolvedValue(undefined)
    vi.mocked(auth.logout).mockResolvedValue(undefined)
  })

  it('changes the password, clears the current session, and returns to login', async () => {
    const { wrapper, router, user } = await mountProfile()
    await wrapper.findAll('button').find(button => button.text() === '修改密码')!.trigger('click')
    await flushPromises()

    const inputs = wrapper.findAll('input[type="password"]')
    await inputs[0]!.setValue('current-password')
    await inputs[1]!.setValue('new-password-123')
    await inputs[2]!.setValue('new-password-123')
    await wrapper.findAll('button').find(button => button.text() === '保存新密码')!.trigger('click')
    await flushPromises()

    expect(auth.changeOwnPassword).toHaveBeenCalledWith({ currentPassword: 'current-password', newPassword: 'new-password-123' })
    expect(auth.logout).toHaveBeenCalledOnce()
    expect(user.token).toBe('')
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('requires the repeated password to match before sending a request', async () => {
    const { wrapper } = await mountProfile()
    await wrapper.findAll('button').find(button => button.text() === '修改密码')!.trigger('click')
    await flushPromises()

    const inputs = wrapper.findAll('input[type="password"]')
    await inputs[0]!.setValue('current-password')
    await inputs[1]!.setValue('new-password-123')
    await inputs[2]!.setValue('different-password-123')
    await wrapper.findAll('button').find(button => button.text() === '保存新密码')!.trigger('click')
    await flushPromises()

    expect(auth.changeOwnPassword).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('两次输入的密码不一致')
  })

  it('shows success after a confirmed mobile logout', async () => {
    const success = vi.spyOn(ElMessage, 'success').mockReturnValue({ close: vi.fn() } as never)
    const { wrapper, router, user } = await mountProfile()

    await wrapper.find('.mobile-profile__logout').trigger('click')
    await flushPromises()

    expect(user.token).toBe('')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(success).toHaveBeenCalledWith('退出成功')
  })

  it('warns when mobile logout cannot confirm server revocation', async () => {
    vi.mocked(auth.logout).mockRejectedValueOnce(new Error('Network Error'))
    const warning = vi.spyOn(ElMessage, 'warning').mockReturnValue({ close: vi.fn() } as never)
    const { wrapper, router, user } = await mountProfile()

    await wrapper.find('.mobile-profile__logout').trigger('click')
    await flushPromises()

    expect(user.token).toBe('')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(warning).toHaveBeenCalledWith('本机已退出，服务端注销未确认')
  })
})
