import ElementPlus from 'element-plus'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  getApprovalTasks: vi.fn(),
  getWorkflowTemplates: vi.fn(),
  getApprovalTemplateOptions: vi.fn()
}))

vi.mock('@/api/modules/approval', () => api)

import ApprovalTaskList from '../ApprovalTaskList.vue'

let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.clearAllMocks()
  api.getApprovalTasks.mockResolvedValue([{
    taskId: 42,
    objectType: 'product',
    objectName: '新款耳机项目',
    nodeName: '工程方案确认',
    initiator: '产品经理',
    approver: '工程负责人',
    dueDate: '2026-11-20',
    status: 'pending',
    targetPath: '/target'
  }])
  api.getWorkflowTemplates.mockResolvedValue([])
  api.getApprovalTemplateOptions.mockResolvedValue({ flowTypes: [], statuses: [], fileCategories: [] })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('ApprovalTaskList mobile task view', () => {
  it('opens directly to task cards from the mobile workbench link', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/approval-tasks', component: ApprovalTaskList },
        { path: '/:pathMatch(.*)*', component: { template: '<div>target</div>' } }
      ]
    })
    await router.push('/approval-tasks?view=tasks')
    await router.isReady()
    wrapper = mount({ template: '<router-view />' }, { global: { plugins: [ElementPlus, router] } })
    await flushPromises()

    expect(router.currentRoute.value.query.view).toBe('tasks')
    expect(wrapper.get('.task-mobile-list').text()).toContain('新款耳机项目')
    expect(wrapper.get('.task-mobile-list').text()).toContain('工程方案确认')
    expect(wrapper.find('.workflow-mobile-list').exists()).toBe(false)
  })
})
