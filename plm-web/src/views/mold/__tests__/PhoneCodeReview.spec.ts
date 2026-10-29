import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus, { ElDialog, ElForm, ElMessage } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PhoneModelReviewRow } from '@/api/modules/code'

const api = vi.hoisted(() => ({ getPhoneModelReview: vi.fn(), confirmPhoneModelReview: vi.fn() }))
vi.mock('@/api/modules/code', async original => ({ ...await original<object>(), ...api }))
vi.mock('@/api/modules/phoneReference', async original => ({
  ...await original<object>(),
  getPhoneSelectionBrands: vi.fn().mockResolvedValue([{id:1,brandId:1,name:"Apple",status:"enabled"}]),getPhoneModels: vi.fn().mockResolvedValue({ content: [], totalElements: 0 }),
  getPhoneModelOptions: vi.fn().mockResolvedValue([])
}))

import PhoneCodeReview from '../components/PhoneCodeReview.vue'
import SharedCompatiblePhoneSelector from '../components/SharedCompatiblePhoneSelector.vue'
import { useUserStore } from '@/stores/user'
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace'

const original = 'IP 17 Pro / IP18 Pro · 5G 两孔型'
const reviewRow = (): PhoneModelReviewRow => ({
  key: '0064', codeValues: ['0064'], modelNames: [original, 'Apple iPhone 18 Pro'], sourceCount: 2,
  sourceDigest: 'current-source-digest', reviewState: 'pending', canReview: true,
  references: [{ projectId: 33, productType: 'model_variant', model: original, phoneModelCode: '0064', phoneModelId: null }]
})
const page = (content: PhoneModelReviewRow[]) => ({ content, page: 1, size: 50, totalElements: content.length, totalPages: content.length ? 1 : 0 })
let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  api.getPhoneModelReview.mockResolvedValue(page([reviewRow()]))
  api.confirmPhoneModelReview.mockResolvedValue({ ...reviewRow(), reviewState: 'confirmed' })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  ElMessage.closeAll()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

async function start(props:Record<string,unknown>={}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  useUserStore().token = 'history-review-test'
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/history', component: { template: '<div />' } },
    { path: '/projects', component: { template: '<div />' } }
  ] })
  await router.push('/history')
  await router.isReady()
  wrapper = mount(PhoneCodeReview, { props, attachTo: document.body, global: {
    plugins: [ElementPlus, pinia, router],
    // Isolate Select popup geometry. Keep the dialog's native Teleport and real form controls.
    stubs: {
      Transition: false,
      TransitionGroup: false,
      SharedCompatiblePhoneSelector: { props: ['modelValue', 'disabled'], template: '<div data-test="standard-phone-selection" />' },
      ElSelect: { props: ['modelValue', 'disabled', 'multiple'], emits: ['update:modelValue', 'change'], template: '<select :value="modelValue" :disabled="disabled" :multiple="multiple" @change="$emit(\'update:modelValue\',$event.target.value);$emit(\'change\',$event.target.value)"><slot /></select>' },
      ElOption: { props: ['value', 'label', 'disabled'], template: '<option :value="value" :disabled="disabled">{{label}}</option>' }
    }
  } })
  await flushPromises()
  return router
}
async function open(text = '核对并保存') {
  await wrapper!.findAll('button').find(button => button.text() === text)!.trigger('click')
  await flushPromises()
}
function dialog() { return new DOMWrapper(document.querySelector<HTMLElement>('.phone-history-review-dialog')!) }
function reviewDialog() { return wrapper!.findAllComponents(ElDialog).find(component => component.props('title') === '核对历史手机关系')! }
async function fill(reason = '已核对原记录，组合型号保留原文') {
  await dialog().get('select[aria-label="历史核对结论"]').setValue('combined_original_preserved')
  await dialog().get('textarea[aria-label="历史核对依据"]').setValue(reason)
  await flushPromises()
}
async function save() { await dialog().get('[data-test="save-history-review"]').trigger('click'); await flushPromises() }

describe('historical phone review conclusions', () => {
  it('opens the requested history category and saved-state filter from the independent module deep link',async()=>{
    await start({initialKind:'name_many_codes',initialReviewState:'confirmed',keyword:'0064'})
    expect(api.getPhoneModelReview).toHaveBeenCalledExactlyOnceWith({kind:'name_many_codes',page:1,size:50,keyword:'0064',reviewState:'confirmed'})
    await wrapper!.setProps({initialKind:'code_many_names',initialReviewState:''});await flushPromises()
    expect(api.getPhoneModelReview).toHaveBeenLastCalledWith({kind:'code_many_names',page:1,size:50,keyword:'0064'})
    expect(api.confirmPhoneModelReview).not.toHaveBeenCalled()
  })
  it('requires both an explicit conclusion and a nonblank reason before calling the confirmation API', async () => {
    await start(); await open(); await save()
    expect(dialog().text()).toContain('请选择核对结论并填写依据')
    expect(api.confirmPhoneModelReview).not.toHaveBeenCalled()
    await dialog().get('textarea[aria-label="历史核对依据"]').setValue('已查阅原资料')
    await save()
    expect(api.confirmPhoneModelReview).not.toHaveBeenCalled()
    await fill('   '); await save()
    expect(dialog().text()).toContain('请选择核对结论并填写依据')
    expect(api.confirmPhoneModelReview).not.toHaveBeenCalled()
  })

  it('preserves combination text and saves its conclusion without requiring a phone or project link, then refreshes pending rows', async () => {
    const source = reviewRow()
    api.getPhoneModelReview.mockResolvedValueOnce(page([source])).mockResolvedValue(page([]))
    await start(); await open()
    expect(dialog().text()).toContain(original)
    expect(wrapper!.getComponent(SharedCompatiblePhoneSelector).props('modelValue')).toEqual([])
    await fill('  两款及孔型均按原文保留，不拆分  '); await save()
    expect(api.confirmPhoneModelReview).toHaveBeenCalledExactlyOnceWith({
      kind: 'code_many_names', key: '0064', sourceDigest: 'current-source-digest',
      conclusion: 'combined_original_preserved', reason: '两款及孔型均按原文保留，不拆分', phoneModelIds: []
    })
    expect(api.getPhoneModelReview).toHaveBeenCalledTimes(2)
    expect(api.getPhoneModelReview).toHaveBeenLastCalledWith({ kind: 'code_many_names', page: 1, size: 50, reviewState: 'pending' })
    expect(useCollaborationWorkspace().revision).toBe(1)
    expect(reviewDialog().props('modelValue')).toBe(false)
    expect(wrapper!.findAll('button').some(button => button.text() === '核对并保存')).toBe(false)
    expect(source.modelNames).toEqual([original, 'Apple iPhone 18 Pro'])
    expect(source.references?.[0]).toMatchObject({ model: original, phoneModelCode: '0064', phoneModelId: null })
  })

  it('retains the chosen conclusion and evidence after a 409 and shows the specific conflict for retry', async () => {
    const conflict = '这组历史引用已变化，请刷新后逐项核对'
    api.confirmPhoneModelReview.mockRejectedValueOnce(Object.assign(new Error(conflict), { status: 409 }))
    await start(); await open(); await fill('原记录人工核对依据'); await save()
    expect(dialog().text()).toContain(conflict)
    expect((dialog().get('select[aria-label="历史核对结论"]').element as HTMLSelectElement).value).toBe('combined_original_preserved')
    expect((dialog().get('textarea[aria-label="历史核对依据"]').element as HTMLTextAreaElement).value).toBe('原记录人工核对依据')
    expect(reviewDialog().props('modelValue')).toBe(true)
    expect(api.getPhoneModelReview).toHaveBeenCalledTimes(1)
    expect(useCollaborationWorkspace().revision).toBe(0)
    await save()
    expect(api.confirmPhoneModelReview.mock.calls[1]?.[0]).toEqual(api.confirmPhoneModelReview.mock.calls[0]?.[0])
  })

  it('allows a readonly actor to view the original sources and prior conclusion without a save action', async () => {
    api.getPhoneModelReview.mockResolvedValue(page([{ ...reviewRow(), canReview: false, reviewState: 'confirmed', conclusion: 'combined_original_preserved', reviewReason: '原始组合经工程核对保留' }]))
    await start(); await open('查看核对资料')
    expect(dialog().text()).toContain(original)
    expect(dialog().text()).toContain('当前账号可查看资料')
    expect((dialog().get('textarea[aria-label="历史核对依据"]').element as HTMLTextAreaElement).value).toBe('原始组合经工程核对保留')
    expect(wrapper!.findAllComponents(ElForm).find(form => form.props('labelPosition') === 'top')!.props('disabled')).toBe(true)
    expect(dialog().find('[data-test="save-history-review"]').exists()).toBe(false)
    expect(api.confirmPhoneModelReview).not.toHaveBeenCalled()
    expect(useCollaborationWorkspace().revision).toBe(0)
  })

  it('closes the review and emits navigation before opening the exact source project', async () => {
    const router = await start(); await open()
    await dialog().findAll('button').find(button => button.text() === '查看项目')!.trigger('click')
    await flushPromises()
    expect(wrapper!.emitted('navigate')).toEqual([[]])
    expect(reviewDialog().props('modelValue')).toBe(false)
    expect(router.currentRoute.value.path).toBe('/projects')
    expect(router.currentRoute.value.query.productId).toBe('33')
    expect(api.confirmPhoneModelReview).not.toHaveBeenCalled()
  })

  it('ignores a pending list response after logout instead of restoring the prior account records', async () => {
    let finish!: (value: ReturnType<typeof page>) => void
    api.getPhoneModelReview.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    await start()
    useUserStore().clearSession(); await flushPromises()
    finish(page([reviewRow()])); await flushPromises()
    expect(wrapper!.text()).not.toContain(original)
    expect(wrapper!.findAll('button').some(button => button.text() === '核对并保存')).toBe(false)
    expect(api.confirmPhoneModelReview).not.toHaveBeenCalled()
  })

  it('does not announce, refresh or invalidate pages for an old confirmation that finishes after logout', async () => {
    let finish!: (value: PhoneModelReviewRow) => void
    const success = vi.spyOn(ElMessage, 'success')
    api.confirmPhoneModelReview.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    await start(); await open(); await fill(); await save()
    expect(api.confirmPhoneModelReview).toHaveBeenCalledTimes(1)
    useUserStore().clearSession(); await flushPromises()
    const revisionAfterLogout = useCollaborationWorkspace().revision
    finish({ ...reviewRow(), reviewState: 'confirmed' }); await flushPromises()
    expect(success).not.toHaveBeenCalled()
    expect(api.getPhoneModelReview).toHaveBeenCalledTimes(1)
    expect(useCollaborationWorkspace().revision).toBe(revisionAfterLogout)
    expect(reviewDialog().props('modelValue')).toBe(false)
  })
})
