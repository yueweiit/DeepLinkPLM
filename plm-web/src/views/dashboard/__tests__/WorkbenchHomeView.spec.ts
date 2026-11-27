import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import WorkbenchHomeView from '../WorkbenchHomeView.vue'
import { workbenchHomeRoute } from '../workbenchHomeRoutes'
import { getHomePage, getHomeSummary, homeCount, type HomeItem, type HomePage, type HomeSource, type HomeSummary, type SourceCount } from '@/api/modules/workbenchHome'
import { useUserStore } from '@/stores/user'

vi.mock('@/api/modules/workbenchHome', async () => ({ ...await vi.importActual<object>('@/api/modules/workbenchHome'), getHomePage: vi.fn(), getHomeSummary: vi.fn() }))
const count = (source: HomeSource, total: number | null, state: SourceCount['state'] = 'ok'): SourceCount => ({ source, total, state, readAt: '2026-11-09', sourceUpdatedAt: null })
const summary = (): HomeSummary => ({ tasks: [count('schedule', 25)], projects: [count('research', 31)], approvals: [count('generic', 2), count('mold_application', null, 'identity_unbound')], exceptions: [count('schedule', 0)] })
const page = (content: HomeItem[] = [], source: HomeSource = 'schedule'): HomePage => ({ content, source, page: 1, size: 20, totalElements: 25, totalPages: 2, sourceState: 'ok', readAt: '2026-11-09', sourceUpdatedAt: null })
let wrapper: VueWrapper
async function start() {
  const pinia = createPinia(); setActivePinia(pinia); useUserStore().token = 'account-A'
  const router = createRouter({ history: createMemoryHistory(), routes: ['/dashboard', '/product-research', '/mold-schedules', '/approval-tasks', '/mold-applications'].map(path => ({ path, component: { template: '<div />' } })) })
  await router.push('/dashboard'); await router.isReady()
  wrapper = mount(WorkbenchHomeView, { global: { plugins: [pinia, router, ElementPlus] } }); await flushPromises()
  return router
}
beforeEach(() => { vi.clearAllMocks(); vi.mocked(getHomeSummary).mockResolvedValue(summary()); vi.mocked(getHomePage).mockResolvedValue(page()) })
afterEach(() => wrapper?.unmount())

describe('workbench home', () => {
  it('keeps unavailable counts unknown and marks partial totals', () => {
    expect(homeCount([count('generic', null, 'restricted')])).toEqual({ total: null, partial: true })
    expect(homeCount([count('generic', 0)])).toEqual({ total: 0, partial: false })
    expect(homeCount(summary().approvals)).toEqual({ total: 2, partial: true })
    expect(homeCount([count('mold_application', 9, 'stale')]).total).toBeNull()
  })
  it('uses server totals and explicitly names the scope of personal tasks', async () => {
    await start()
    expect(wrapper.get('[data-card="tasks"] strong').text()).toBe('25')
    expect(wrapper.text()).toContain('当前覆盖：排程个人及职责组任务')
    expect(wrapper.get('[data-card="approvals"]').text()).toContain('部分来源不可用')
    expect(getHomePage).toHaveBeenCalledWith('tasks', expect.objectContaining({ source: 'schedule', page: 1, size: 20 }), expect.anything())
  })
  it('navigates into the exact official research stage without completing it', async () => {
    const item: HomeItem = { key: 'research:42', source: 'research', target: 'research', businessId: '42', projectId: 42, title: '天眼阶梯', stage: 4, stageKey: 'sample-confirm', stageName: '样品确认' }
    vi.mocked(getHomePage).mockResolvedValue(page([item], 'research'))
    const router = await start(); await wrapper.get('[data-card="projects"]').trigger('click'); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '进入原页面')!.trigger('click'); await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ productId: '42', step: 'sample-confirm' })
    expect(router.currentRoute.value.path).toBe('/product-research')
    expect(wrapper.text()).toContain('具体缺项和能否推进请进入项目查看')
  })
  it('does not replace failure with a zero or a business-empty message', async () => {
    vi.mocked(getHomePage).mockRejectedValue(new Error('offline')); await start()
    expect(wrapper.text()).toContain('事项加载失败')
    expect(wrapper.text()).not.toContain('当前来源暂无符合条件的事项')
  })
  it('clears and discards results from the previous login, including late summaries', async () => {
    let resolveOld!: (value: HomePage) => void, resolveSummary!: (value: HomeSummary) => void
    vi.mocked(getHomePage).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    vi.mocked(getHomeSummary).mockImplementationOnce(() => new Promise(resolve => { resolveSummary = resolve }))
    await start(); useUserStore().token = 'account-B'; await flushPromises()
    resolveOld(page([{ key: 'private', title: 'A账号私密事项', businessId: '1', source: 'schedule', target: 'schedule' }]))
    resolveSummary({ ...summary(), tasks: [count('schedule', 999)] }); await flushPromises()
    expect(wrapper.text()).not.toContain('A账号私密事项')
    expect(wrapper.get('[data-card="tasks"] strong').text()).toBe('25')
  })
  it('renders approval still running separately from existing execution authorization', async () => {
    vi.mocked(getHomePage).mockResolvedValue({ ...page([{ key: 'mold_application:7:t1', source: 'mold_application', target: 'mold_application', businessId: '7', applicationId: 7, title: '开模申请', approvalGranted: true, approvalFlowState: 'awaiting_approval' }], 'mold_application'), sourceState: 'stale' })
    await start(); await wrapper.get('[data-card="approvals"]').trigger('click'); await flushPromises()
    await wrapper.get('input[value="mold_application"]').setValue(true); await flushPromises()
    expect(getHomePage).toHaveBeenLastCalledWith('approvals', expect.objectContaining({ source: 'mold_application' }), expect.anything())
    expect(wrapper.text()).toContain('审批中'); expect(wrapper.text()).toContain('已取得开工授权')
    expect(wrapper.text()).toContain('同步时间未知'); expect(wrapper.text()).not.toContain('整单已审批完成')
  })
  it('only produces supported business routes', () => {
    expect(workbenchHomeRoute({ target: 'research', projectId: 42, stageKey: 'wrong' } as HomeItem)).toBeNull()
    expect(workbenchHomeRoute({ target: 'mold_application', applicationId: 7 } as HomeItem)).toEqual({ path: '/mold-applications', query: { applicationId: 7 } })
    expect(workbenchHomeRoute({ target: 'schedule', moldSetId: 8, taskKey: 'ship' } as HomeItem)).toEqual({ path: '/mold-schedules', query: { moldSetId: 8, taskKey: 'ship', tab: 'tasks' } })
  })
})
