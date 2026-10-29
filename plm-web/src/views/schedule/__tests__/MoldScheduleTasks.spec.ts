import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus, { ElMessageBox, ElSelect } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ScheduleOptions, ScheduleRow, ScheduleTask } from '@/api/modules/schedule'
import type { PlanField } from '@/types/collaboration'

const api = vi.hoisted(() => ({ getSchedulePage:vi.fn(),getScheduleApplications:vi.fn(),getScheduleOptions: vi.fn(), getScheduleTasks: vi.fn(), getSchedule: vi.fn(), saveScheduleRows: vi.fn(), getScheduleHistory: vi.fn(), confirmSchedulePlan: vi.fn() }))
const approvals = vi.hoisted(() => ({ getApprovalTasks: vi.fn() }))
const partnerTasks=vi.hoisted(()=>({operationTasks:vi.fn().mockResolvedValue([]),operationTarget:(kind:string,id:number)=>({path:`/partner-operations/${kind}/${id}`})}))
const resize=vi.hoisted(()=>({callbacks:[] as ((entries:{contentRect:{width:number}}[])=>void)[]}))
vi.mock('@vueuse/core',async original=>({...await original<object>(),useResizeObserver:vi.fn((_target,callback)=>{resize.callbacks.push(callback);return {stop:vi.fn()}})}))
vi.mock('@/api/modules/schedule', () => api)
vi.mock('@/api/modules/approval', () => approvals)
vi.mock('@/api/modules/partnerOperations',()=>partnerTasks)
vi.mock('@/components/FixedTableViewport/index.vue', () => ({ default: { template: '<div><slot :table-height="480" /></div>' } }))
vi.mock('@/views/collaboration/components/CollaborationImportDialog.vue',()=>({default:{template:'<div />'}}))
vi.mock('../components/MoldSheetSyncControls.vue',()=>({default:{template:'<div data-test="sheet-sync-stub" />'}}))
import MoldScheduleView from '../MoldScheduleView.vue'
import MoldTaskList from '../components/MoldTaskList.vue'
import ScheduleTaskEditor from '../components/ScheduleTaskEditor.vue'
import { moldScheduleTaskRoute } from '@/utils/collaboration'
import MyTasksView from '@/views/pilotdelivery/MyTasksView.vue'
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace'
import { useUserStore } from '@/stores/user'

const field = (key: string, label: string, group: PlanField['group'] = 'logistics', kind = 'date'): PlanField => ({ key, label, group, kind, maxLength: 0 })
const fields = [field('openedDate', '开模日期', 'engineering'), field('plannedMoldDeliveryDate', '计划模具交付日期', 'engineering'), field('actualShipDate', '实际发模日期'), field('actualMexicoArrivalDate', '实际抵达墨西哥日期'), field('plannedFactoryArrivalDate', '计划到墨西哥工厂日期'), field('forecastDeliveryDate', '当前预测模具交付日期', 'engineering'), field('forecastFactoryDate', '当前预测到厂日期'), field('logisticsOwnerUserId', '物流负责人', 'logistics', 'id')]
const options: ScheduleOptions = { administrator: true, userId: 7, users: [{ userId: 7, username: 'tony', displayName: 'Tony' }], productLines: [], itemFields: [], moldFields: fields, scheduleFields: fields, writableGroups: ['engineering', 'logistics', 'mexico'], styleDays: {}, transportDays: 7, acceptanceDays: 1, productionDays: 3, remindersExternalEnabled: false }
const row = (id = 1): ScheduleRow => ({ itemId: id, moldSetId: id, assetNumber: `MS-${id}`, styleName: 'Fantasía', compatibleModels: 'Apple iPhone 18 Pro Max', brand: 'Apple', productLineId: 50, phoneModelCode: null, phonePrice: null, priceCurrency: null, salesPlatform: null, referenceUrl: null, notes: null, pcTotal: null, tpuTotal: null, finishedTotal: null, productionNotes: null, projectCount: 0, projects: [], moldSetCount: 1, status: 'ready', updatedAt: '2026-10-13', openedDate: '2026-09-01', plannedMoldDeliveryDate: '2026-10-10', plannedFactoryArrivalDate: null, actualShipDate: null, planVersion: 0, baseline: null, baselineNeedsReview: false, marketEligible: false, scheduleStatus: 'ready', tasks: [], suggestedDeliveryDate: null, suggestedFactoryDate: '2026-10-17', suggestedAcceptanceDate: null, suggestedProductionDate: null })
const task = (key = 'ship', id = 1): ScheduleTask => ({ itemId: id, moldSetId: id, assetNumber: `MS-${id}`, styleName: 'Fantasía', compatibleModels: 'Apple iPhone 18 Pro Max', taskKey: key, title: key === 'ship' ? '填写实际发模' : '补填到厂计划', group: 'logistics', ownerUserId: 7, dueDate: key === 'ship' ? '2026-10-10' : null, overdue: key === 'ship', overdueDays: key === 'ship' ? 3 : 0, category: key === 'ship' ? 'milestone' : 'missing', inputFields: key === 'ship' ? ['actualShipDate'] : ['plannedFactoryArrivalDate'], supportFields: ['forecastFactoryDate', 'logisticsOwnerUserId'] })
const page = (content: ScheduleTask[]) => ({ content, page: 1, size: 50, totalElements: content.length, totalPages: content.length ? 1 : 0 })
let wrapper: VueWrapper | undefined
beforeEach(() => { vi.clearAllMocks();partnerTasks.operationTasks.mockResolvedValue([]);resize.callbacks=[]; localStorage.clear(); api.getSchedulePage.mockResolvedValue({content:[],totalElements:0}); api.getScheduleApplications.mockResolvedValue({content:[],totalElements:0}); api.getScheduleOptions.mockResolvedValue(options); api.getScheduleTasks.mockResolvedValue(page([task()])); api.getSchedule.mockImplementation(async (id: number) => row(id)); api.getScheduleHistory.mockResolvedValue({ content: [], totalElements: 0 }); api.saveScheduleRows.mockImplementation(async patches => patches.map((p: { id: number; changes: { field: string; after: unknown }[] }) => ({ entityType: 'mold_set', id: p.id, status: 'saved', message: 'saved', conflicts: [], row: { ...row(p.id), ...Object.fromEntries(p.changes.map(c => [c.field, c.after])) } }))); approvals.getApprovalTasks.mockResolvedValue([{ instanceId: 'A1' }]) })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.useRealTimers(); vi.restoreAllMocks() })
async function start(path = '/mold-schedules?tab=tasks', permissions = ['*']) {
  const pinia = createPinia(); setActivePinia(pinia); const user = useUserStore(); user.token = 'test'; user.permissions = permissions
  const router = createRouter({ history: createMemoryHistory(), routes: [{path:'/mold-delivery-tasks',redirect:to=>({...moldScheduleTaskRoute(to.query),hash:to.hash})},{path:'/mold-schedules',component:MoldScheduleView}, { path: '/my-tasks', component: MyTasksView }, { path: '/molds', component: { template: '<p>ledger</p>' } },{path:'/dashboard',component:{template:'<p>dashboard</p>'}}] })
  await router.push(path); await router.isReady(); wrapper = mount({ template: '<router-view v-slot="{Component}"><KeepAlive include="MoldScheduleView"><component :is="Component"/></KeepAlive></router-view>' }, { attachTo: document.body, global: { plugins: [pinia, router, ElementPlus], stubs:{ElDrawer:{name:'ElDrawer',props:['modelValue'],emits:['update:modelValue','close'],template:'<section v-if="modelValue"><slot/><slot name="footer"/></section>'}} } }); await flushPromises(); return { v: wrapper, router }
}
async function select(taskIndex = 0) { wrapper!.findComponent(MoldTaskList).vm.$emit('select', wrapper!.findComponent(MoldTaskList).props('tasks')[taskIndex]); await flushPromises() }

describe('mold tasks merged into schedule control', () => {
  it('redirects legacy bookmarks into the canonical schedule task tab without losing filters or focus',async()=>{
    const {v,router}=await start('/mold-delivery-tasks?itemId=1&moldSetId=1&taskKey=ship#history')
    expect(router.currentRoute.value.path).toBe('/mold-schedules');expect(router.currentRoute.value.query).toEqual({itemId:'1',moldSetId:'1',taskKey:'ship',tab:'tasks'});expect(router.currentRoute.value.hash).toBe('#history')
    expect(v.get('[data-test="schedule-tasks-tab"] input').element).toHaveProperty('checked',true);expect(v.findComponent(ScheduleTaskEditor).props('task')).toMatchObject({moldSetId:1,taskKey:'ship'});expect(v.find('.wide-mode').exists()).toBe(false)
  })
  it('locates an authorized task outside the current page using the exact task query',async()=>{
    api.getScheduleTasks.mockImplementation(async query=>page(query.taskKey?[task('factory_plan',2)]:[task()]))
    const {v,router}=await start('/mold-schedules?tab=tasks&selectedMoldSetId=2&taskKey=factory_plan')
    expect(api.getScheduleTasks).toHaveBeenCalledWith(expect.objectContaining({page:1,size:1,moldSetId:2,taskKey:'factory_plan',mineOnly:true,category:'all'}));expect(v.findComponent(MoldTaskList).props('tasks')).toHaveLength(1)
    expect(v.findComponent(ScheduleTaskEditor).props('task')).toMatchObject({moldSetId:2,taskKey:'factory_plan'});expect(api.getSchedule).toHaveBeenCalledWith(2);expect(router.currentRoute.value.query.moldSetId).toBeUndefined()
  })
  it('closes the task editor without dropping a mold filter or unsaved task fields',async()=>{
    const {v,router}=await start('/mold-schedules?tab=tasks&moldSetId=1&taskKey=ship');await v.get('input[aria-label="实际发模日期"]').setValue('2026-10-12')
    v.findAllComponents({name:'ElDrawer'}).find(d=>d.props('modelValue'))!.vm.$emit('close');await flushPromises()
    expect(router.currentRoute.value.query).toEqual({tab:'tasks',moldSetId:'1'});expect(v.findComponent(ScheduleTaskEditor).exists()).toBe(false);expect(useCollaborationWorkspace().drafts.patches()).toMatchObject([{id:1,changes:[{field:'actualShipDate',after:'2026-10-12'}]}])
    await select();expect((v.get('input[aria-label="实际发模日期"]').element as HTMLInputElement).value).toBe('2026-10-12')
  })
  it('shares date drafts between schedule and tasks without exposing batch save in the task tab',async()=>{
    api.getSchedulePage.mockResolvedValue({content:[row()],totalElements:1})
    const {v}=await start('/mold-schedules');expect(api.getScheduleTasks).not.toHaveBeenCalled();await v.get('input[aria-label="实际发模日期"]').setValue('2026-10-12')
    await v.get('[data-test="schedule-tasks-tab"] input').setValue(true);await flushPromises();await select()
    expect((v.get('input[aria-label="实际发模日期"]').element as HTMLInputElement).value).toBe('2026-10-12');expect(v.find('[data-test="save-schedules"]').exists()).toBe(false)
    await v.get('[data-test="schedule-sets-tab"] input').setValue(true);await flushPromises();expect((v.get('input[aria-label="实际发模日期"]').element as HTMLInputElement).value).toBe('2026-10-12');expect(v.find('[data-test="save-schedules"]').exists()).toBe(true);expect(api.saveScheduleRows).not.toHaveBeenCalled()
  })
  it('uses server pagination and category filters without changing the saved schedule filter',async()=>{
    const {v}=await start();v.findComponent(MoldTaskList).vm.$emit('page',2);await flushPromises();expect(api.getScheduleTasks).toHaveBeenLastCalledWith(expect.objectContaining({page:2,category:'all',mineOnly:true}))
    const category=v.findAllComponents(ElSelect).find(select=>select.find('[aria-label="待办类别"]').exists())!;category.vm.$emit('update:modelValue','review');await flushPromises()
    expect(api.getScheduleTasks).toHaveBeenLastCalledWith(expect.objectContaining({page:1,category:'review',mineOnly:true}));expect(api.saveScheduleRows).not.toHaveBeenCalled()
  })
  it('reuses saved modification history and offers retry without a replacement edit scope after a failed detail request',async()=>{
    api.getSchedule.mockRejectedValueOnce(new Error('offline'));const {v}=await start();await select();expect(v.text()).toContain('当前任务资料加载失败');expect(v.find('input[aria-label="实际发模日期"]').exists()).toBe(false)
    await v.findAll('button').find(b=>b.text()==='重试')!.trigger('click');await flushPromises();expect(v.find('input[aria-label="实际发模日期"]').exists()).toBe(true)
    await v.findAll('button').find(b=>b.text()==='修改历史')!.trigger('click');await flushPromises();expect(api.getScheduleHistory).toHaveBeenCalledWith(1);expect(api.saveScheduleRows).not.toHaveBeenCalled()
  })
  it('keeps complete task identity, dates and actions when the content container becomes narrow',async()=>{
    const {v}=await start();resize.callbacks.at(-1)!([{contentRect:{width:400}}]);await flushPromises()
    const record=v.get('[data-task-id="1:ship"]');expect(record.text()).toContain('Apple iPhone 18 Pro Max');expect(record.text()).toContain('Fantasía');expect(record.text()).toContain('MS-1');expect(record.text()).toContain('2026-10-10');expect(record.text()).toContain('延期 3 天');expect(record.text()).toContain('物流负责人')
    await record.findAll('button').find(b=>b.text()==='处理')!.trigger('click');await flushPromises();expect(v.findComponent(ScheduleTaskEditor).props('task')).toMatchObject({moldSetId:1,taskKey:'ship'});expect(v.find('.wide-mode').exists()).toBe(false)
  })
  it('shows task-list errors and retries in place without a replacement table or a save',async()=>{
    api.getScheduleTasks.mockRejectedValueOnce(new Error('offline'));const {v}=await start();expect(v.text()).toContain('加载失败，请重试');expect(v.find('.schedule-table').exists()).toBe(false)
    await v.findAll('button').find(b=>b.text()==='刷新')!.trigger('click');await flushPromises();expect(v.findComponent(MoldTaskList).props('tasks')).toHaveLength(1);expect(v.text()).not.toContain('加载失败，请重试');expect(api.saveScheduleRows).not.toHaveBeenCalled()
  })
  it('keeps a still-pending off-page task open after saved-state reevaluation',async()=>{
    api.getScheduleTasks.mockImplementation(async query=>page(query.taskKey?[task('factory_plan',2)]:[task()]))
    const {v}=await start('/mold-schedules?tab=tasks&selectedMoldSetId=2&taskKey=factory_plan');await v.get('input[aria-label="计划到墨西哥工厂日期"]').setValue('2026-10-17')
    useCollaborationWorkspace().changed();await flushPromises();expect(v.findComponent(ScheduleTaskEditor).props('task')).toMatchObject({moldSetId:2,taskKey:'factory_plan'});expect((v.get('input[aria-label="计划到墨西哥工厂日期"]').element as HTMLInputElement).value).toBe('2026-10-17');expect(api.saveScheduleRows).not.toHaveBeenCalled()
  })
  it('preserves task drafts through My Tasks and closes the cached editor while away',async()=>{
    const {v,router}=await start('/mold-schedules?tab=tasks&moldSetId=1&taskKey=ship');await v.get('input[aria-label="实际发模日期"]').setValue('2026-10-12')
    vi.spyOn(ElMessageBox,'confirm').mockImplementation(()=>Promise.resolve('confirm') as ReturnType<typeof ElMessageBox.confirm>);await router.push('/my-tasks');await flushPromises()
    expect(router.currentRoute.value.path).toBe('/my-tasks');expect(v.findComponent(ScheduleTaskEditor).exists()).toBe(false);expect(useCollaborationWorkspace().drafts.patches()).toHaveLength(1)
    await router.push('/mold-schedules?tab=tasks&moldSetId=1&taskKey=ship');await flushPromises();expect((v.get('input[aria-label="实际发模日期"]').element as HTMLInputElement).value).toBe('2026-10-12');expect(api.saveScheduleRows).not.toHaveBeenCalled()
  })
  it('requests all real task categories for the current actor and opens only the selected task fields', async () => {
    api.getScheduleTasks.mockResolvedValue(page([task('factory_plan')]))
    const { v } = await start(); expect(api.getScheduleTasks).toHaveBeenCalledWith(expect.objectContaining({ page: 1, size: 50, category: 'all', mineOnly: true })); expect(api.getSchedule).not.toHaveBeenCalled(); expect(v.text()).toContain('无独立期限'); expect(v.text()).not.toContain('延期')
    await select(); expect(api.getSchedule).toHaveBeenCalledWith(1); expect(v.find('input[aria-label="计划到墨西哥工厂日期"]').exists()).toBe(true); expect(v.find('input[aria-label="开模日期"]').exists()).toBe(false); expect(v.get('details').attributes('open')).toBeUndefined(); expect(api.confirmSchedulePlan).not.toHaveBeenCalled()
  })
  it('keeps multiline task notes directly editable and stages native text paste without spreadsheet parsing', async () => {
    const notes: PlanField = { ...field('notes', '模具备注', 'logistics', 'text'), maxLength: 4000 }
    api.getScheduleOptions.mockResolvedValue({ ...options, moldFields: [...fields, notes] })
    api.getScheduleTasks.mockResolvedValue(page([{ ...task(), supportFields: ['forecastFactoryDate', 'notes'] }]))
    const { v } = await start(); await select()
    const textarea = v.get('textarea[aria-label="模具备注"]')
    expect(textarea.element.closest('details')).toBeNull()
    const text = '第一轮：按键偏硬\n第二轮：修改结构\t待复验'
    const paste = new Event('paste', { bubbles: true, cancelable: true })
    Object.defineProperty(paste, 'clipboardData', { value: { getData: () => text } })
    textarea.element.dispatchEvent(paste)
    expect(paste.defaultPrevented).toBe(false)
    await textarea.setValue(text)
    expect(useCollaborationWorkspace().drafts.patches()).toMatchObject([{ id: 1, changes: [{ field: 'notes', before: null, after: text }] }])
    expect(api.saveScheduleRows).not.toHaveBeenCalled()
    expect((textarea.element as HTMLTextAreaElement).value).toBe(text)
  })
  it('saves only current task fields while preserving other task, mold and archive drafts, and does not complete a draft early', async () => {
    const { v } = await start(); await select(); const drafts = useCollaborationWorkspace().drafts
    drafts.set(row(), 'openedDate', '2026-09-02'); drafts.set(row(), 'pcAcceptanceStatus', 'passed'); drafts.set(row(2), 'actualShipDate', '2026-10-11'); drafts.set({ ...row(3), moldSetId: undefined }, 'pcTotal', 200)
    await v.get('input[aria-label="实际发模日期"]').setValue('2026-10-12'); expect(v.findComponent(MoldTaskList).props('tasks')).toHaveLength(1); expect(api.saveScheduleRows).not.toHaveBeenCalled()
    api.getScheduleTasks.mockResolvedValue(page([])); await v.get('[data-test="save-current-task"]').trigger('click'); await flushPromises()
    expect(api.saveScheduleRows).toHaveBeenCalledWith([{ entityType: 'mold_set', id: 1, changes: [{ field: 'actualShipDate', before: null, after: '2026-10-12', reason: undefined }] }]); expect(v.findComponent(MoldTaskList).props('tasks')).toHaveLength(0)
    expect(drafts.patches()).toEqual(expect.arrayContaining([expect.objectContaining({ id: 1, changes: [expect.objectContaining({ field: 'openedDate', before: '2026-09-01', after: '2026-09-02' }), expect.objectContaining({ field: 'pcAcceptanceStatus', after: 'passed' })] }), expect.objectContaining({ id: 2 }), expect.objectContaining({ entityType: 'item', id: 3 })]))
  })
  it('undoes only the active task scope without clearing hidden shared fields', async () => {
    const { v } = await start(); await select(); const drafts = useCollaborationWorkspace().drafts; drafts.set(row(), 'openedDate', '2026-09-02'); await v.get('input[aria-label="实际发模日期"]').setValue('2026-10-12')
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => Promise.resolve('confirm') as ReturnType<typeof ElMessageBox.confirm>); await v.get('[data-test="discard-current-task"]').trigger('click'); await flushPromises()
    expect(drafts.patches()).toMatchObject([{ id: 1, changes: [{ field: 'openedDate' }] }]); expect(api.saveScheduleRows).not.toHaveBeenCalled()
  })
  it('explicitly confirms a changed-plan review at the existing factory date without overwriting it or confirming a baseline', async () => {
    const review: ScheduleTask = { ...task('arrival_review'), category: 'review', supportFields: ['plannedMoldDeliveryDate', 'arrivalConfirmedBasisDate'] }; api.getScheduleTasks.mockResolvedValue(page([review])); api.getSchedule.mockResolvedValue({ ...row(), plannedFactoryArrivalDate: '2026-10-22', arrivalConfirmedBasisDate: '2026-10-09' })
    const { v } = await start(); await select(); expect(v.text()).toContain('上次依据 2026-10-09'); expect(v.find('[data-test="adopt-task-arrival"]').exists()).toBe(false)
    await v.get('[data-test="confirm-task-arrival"]').trigger('click'); const submitted = [{ entityType: 'mold_set', id: 1, changes: [{ field: 'plannedFactoryArrivalDate', before: '2026-10-22', after: '2026-10-22', reason: undefined }], expectedValues: { plannedMoldDeliveryDate: '2026-10-10', arrivalConfirmedBasisDate: '2026-10-09' } }]; expect(useCollaborationWorkspace().drafts.patches()).toEqual(submitted); expect(api.saveScheduleRows).not.toHaveBeenCalled()
    api.saveScheduleRows.mockResolvedValue([{ entityType: 'mold_set', id: 1, status: 'conflict', message: '核对依据已变化', conflicts: [{ field: 'plannedMoldDeliveryDate', label: '计划模具交付日期', before: '2026-10-10', current: '2026-10-12', attempted: '2026-10-10' }, { field: 'arrivalConfirmedBasisDate', label: '上次核对依据', before: '2026-10-09', current: '2026-10-11', attempted: '2026-10-09' }], row: null }]); await v.get('[data-test="save-current-task"]').trigger('click'); await flushPromises(); expect(api.saveScheduleRows).toHaveBeenCalledWith(submitted); expect(v.get('.task-conflicts').text()).toContain('当前 2026-10-12'); expect(v.get('.task-conflicts').text()).toContain('当前 2026-10-11'); expect(useCollaborationWorkspace().drafts.patches()).toEqual(submitted)
    await v.get('[data-test="review-expected-plannedMoldDeliveryDate"]').trigger('click'); await v.get('[data-test="review-expected-arrivalConfirmedBasisDate"]').trigger('click'); expect(useCollaborationWorkspace().drafts.patches()).toEqual([{ ...submitted[0], expectedValues: { plannedMoldDeliveryDate: '2026-10-12', arrivalConfirmedBasisDate: '2026-10-11' } }]); expect(api.confirmSchedulePlan).not.toHaveBeenCalled()
  })
  it('adopts the existing plus-seven-day suggestion only after confirmation and keeps it as an unsaved task delta', async () => {
    api.getScheduleTasks.mockResolvedValue(page([task('factory_plan')])); const { v } = await start(); await select(); const drafts = useCollaborationWorkspace().drafts; expect(drafts.patches()).toHaveLength(0); drafts.set(row(), 'plannedMoldDeliveryDate', '2026-10-20'); await flushPromises(); expect(v.text()).toContain('2026-10-17（计划交付＋7天）')
    await v.get('[data-test="adopt-task-arrival"]').trigger('click'); expect(drafts.patches({ entity: 'mold_set', id: 1, fields: ['plannedFactoryArrivalDate'] })).toEqual([{ entityType: 'mold_set', id: 1, changes: [{ field: 'plannedFactoryArrivalDate', before: null, after: '2026-10-17', reason: undefined }], expectedValues: { plannedMoldDeliveryDate: '2026-10-10', arrivalConfirmedBasisDate: null } }]); expect(v.findComponent(MoldTaskList).props('tasks')).toHaveLength(1); expect(api.saveScheduleRows).not.toHaveBeenCalled()
  })
  it('fails closed on unassigned writable groups and shows completed deep links without opening a replacement delivery table', async () => {
    api.getScheduleOptions.mockResolvedValue({ ...options, administrator: false, writableGroups: ['engineering'] }); const { v, router } = await start('/mold-delivery-tasks', ['schedule:read', 'schedule:write']); await select(); expect(v.find('input[aria-label="实际发模日期"]').exists()).toBe(false); expect(v.text()).not.toContain('仅本人待办')
    api.getScheduleTasks.mockResolvedValue(page([])); await router.replace('/mold-delivery-tasks?moldSetId=9&taskKey=ship'); await flushPromises(); expect(v.text()).toContain('本套暂无待办'); expect(v.text()).toContain('打开本套排程'); expect(api.getSchedule).toHaveBeenCalledTimes(1); expect(api.saveScheduleRows).not.toHaveBeenCalled()
  })
  it('refreshes without replacing task drafts and discards session data on logout', async () => {
    const { v } = await start(); await select(); await v.get('input[aria-label="实际发模日期"]').setValue('2026-10-12'); const refresh = v.findAll('button').find(b => b.text() === '刷新')!; await refresh.trigger('click'); await flushPromises(); expect((v.get('input[aria-label="实际发模日期"]').element as HTMLInputElement).value).toBe('2026-10-12'); expect(useCollaborationWorkspace().drafts.patches()[0].changes[0].before).toBeNull()
    useUserStore().token = ''; await flushPromises(); expect(useCollaborationWorkspace().drafts.patches()).toHaveLength(0); expect(v.findComponent(MoldTaskList).props('tasks')).toHaveLength(0)
  })
  it('keeps drafts when another task is selected and warns on departure even when the current task has no changes', async () => {
    api.getScheduleTasks.mockResolvedValue(page([task(), task('ship', 2)])); const { v, router } = await start(); await select(); await v.get('input[aria-label="实际发模日期"]').setValue('2026-10-12'); await select(1)
    expect(v.findComponent(MoldTaskList).props('tasks')).toHaveLength(2); expect((v.get('input[aria-label="实际发模日期"]').element as HTMLInputElement).value).toBe(''); expect(v.get('[data-test="save-current-task"]').attributes('disabled')).toBeDefined()
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => Promise.resolve('confirm') as ReturnType<typeof ElMessageBox.confirm>); await router.push('/molds'); await flushPromises(); expect(confirm).toHaveBeenCalledTimes(1); expect(useCollaborationWorkspace().drafts.patches()).toMatchObject([{ id: 1, changes: [{ field: 'actualShipDate', after: '2026-10-12' }] }])
  })
  it('reuses the task table from My Tasks including supplementary tasks while retaining approval tasks and exact task navigation', async () => {
    api.getScheduleTasks.mockResolvedValue(page([task('factory_plan')])); const { v, router } = await start('/my-tasks'); expect(api.getScheduleTasks).toHaveBeenCalledWith(expect.objectContaining({ mineOnly: true, category: 'all' })); expect(v.text()).toContain('流程审批待办 1'); expect(approvals.getApprovalTasks).toHaveBeenCalledTimes(1)
    v.findComponent(MoldTaskList).vm.$emit('select', task('factory_plan')); await flushPromises(); expect(router.currentRoute.value.path).toBe('/mold-schedules'); expect(router.currentRoute.value.query).toMatchObject({ tab:'tasks', moldSetId: '1', taskKey: 'factory_plan' })
  })
})
