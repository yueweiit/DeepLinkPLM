import ElementPlus from 'element-plus'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import { setLanguage } from '@/locales/language'
import MobileHomeView from '../MobileHomeView.vue'

let activeRouter: Router

async function mountHome(permissions: string[], initialPath = '/mobile', roleCode?: string) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const user = useUserStore()
  user.token = 'mobile-preview'
  user.permissions = permissions
  user.profile = { userId: 1, userName: '手机端测试账号', roleName: '测试角色', department: '测试部门', roleCode }
  const app = useAppStore()
  app.phoneWatchConfigLoaded = true
  app.phoneWatchEnabled = true
  app.moldApplicationEnabled = true
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/mobile', component: MobileHomeView },
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } }
    ]
  })
  activeRouter = router
  await router.push(initialPath)
  await router.isReady()
  const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [ElementPlus, pinia, router] } })
  await flushPromises()
  return wrapper
}

describe('MobileHomeView', () => {
  beforeEach(() => { localStorage.clear(); setLanguage('zh-CN') })

  it('shows only modules allowed to the signed-in role', async () => {
    const wrapper = await mountHome(['partner:view', 'pilot:tasks'])
    const text = wrapper.text()
    expect(text).toContain('我的待办')
    expect(text).toContain('选品目录')
    expect(text).not.toContain('新品研发')
    expect(text).not.toContain('账号与权限')
    expect(text).not.toContain('系统日志')
    await wrapper.get('[aria-label="全部业务"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('供应商产品目录')
    expect(wrapper.text()).not.toContain('新品研发')
  })

  it('opens a permission-filtered module directory from the more view', async () => {
    const wrapper = await mountHome(['*'], '/mobile?view=more')
    const text = wrapper.text()
    expect(text).toContain('审批任务')
    expect(text).toContain('测试管理')
    expect(text).toContain('账号与权限')
    expect(text).toContain('角色管理')
    expect(text).toContain('系统日志')
    expect(text).toContain('数据导入')
  })

  it('keeps system modules out of the directory when the role lacks their permissions', async () => {
    const wrapper = await mountHome(['project:view'], '/mobile?view=more')
    expect(wrapper.text()).not.toContain('账号与权限')
    expect(wrapper.text()).not.toContain('角色管理')
    expect(wrapper.text()).not.toContain('系统日志')
    expect(wrapper.text()).not.toContain('数据导入')
  })

  it('shows local samples to an arrival-only Mexico account without exposing partner administration', async () => {
    const wrapper = await mountHome(['task:arrival'], '/mobile?view=more', 'mexico')
    expect(wrapper.text()).toContain('样品管理')
    expect(wrapper.text()).not.toContain('供应商产品目录')
    expect(wrapper.text()).not.toContain('账号与权限')
  })

  it('searches across authorized modules in both the home and more views', async () => {
    const wrapper = await mountHome(['*'])
    await wrapper.get('.mobile-home__search input').setValue('BOM')
    expect(wrapper.text()).toContain('BOM 管理')
    expect(wrapper.text()).not.toContain('开模时间管控')
    await wrapper.get('.mobile-home__search input').setValue('')
    await wrapper.get('[aria-label=\"全部业务\"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.mobile-home--more').exists()).toBe(true)
    await wrapper.get('.mobile-home__search input').setValue('BOM')
    expect(wrapper.text()).toContain('BOM 管理')
    expect(wrapper.text()).not.toContain('开模时间管控')
  })

  it('shows the account details and language setting in the personal center', async () => {
    const wrapper = await mountHome(['*'], '/mobile?view=profile')
    expect(wrapper.text()).toContain('账号信息')
    expect(wrapper.text()).toContain('手机端测试账号')
    expect(wrapper.text()).toContain('测试部门')
    expect(wrapper.find('select.language-switcher').exists()).toBe(true)
    expect(wrapper.text()).toContain('账号安全')
    expect(wrapper.findAll('button').map(button => button.text())).toContain('修改密码')
    expect(wrapper.findAll('button').map(button => button.text())).toContain('退出登录')
  })

  it('keeps the entry source when opening a non-root module', async () => {
    const wrapper = await mountHome(['partner:view'])
    await wrapper.findAll('.mobile-home__quick-entry').find(button => button.text().includes('选品目录'))!.trigger('click')
    await flushPromises()
    expect(activeRouter.currentRoute.value.path).toBe('/partner-selections')
    expect(activeRouter.currentRoute.value.query).toEqual({ mobileFrom: '/mobile' })
  })

  it('returns to the directory source and skips the source for a bottom navigation root', async () => {
    const wrapper = await mountHome(['partner:view'], '/mobile?view=more')
    const entries = wrapper.findAll('.mobile-home__module')
    await entries.find(button => button.text().includes('选品目录'))!.trigger('click')
    await flushPromises()
    expect(activeRouter.currentRoute.value.path).toBe('/partner-selections')
    expect(activeRouter.currentRoute.value.query).toEqual({ mobileFrom: '/mobile?view=more' })

    await activeRouter.push('/mobile?view=more')
    await flushPromises()
    await wrapper.findAll('.mobile-home__module').find(button => button.text().includes('供应商产品目录'))!.trigger('click')
    await flushPromises()
    expect(activeRouter.currentRoute.value.path).toBe('/partner-products')
    expect(activeRouter.currentRoute.value.query).toEqual({})
  })

  it('translates mobile catalog headings, entry labels, and search when Spanish is selected', async () => {
    setLanguage('es')
    const wrapper = await mountHome(['*'], '/mobile?view=more')
    expect(wrapper.text()).toContain('Desarrollo y pedidos')
    expect(wrapper.get('.mobile-home__search input').attributes('placeholder')).toBe('Buscar módulos')
    expect(wrapper.text()).toContain('Moldes e ingeniería')
    expect(wrapper.text()).not.toContain('研发与订单')
    expect(wrapper.text()).not.toContain('模具与工程')
  })
})
