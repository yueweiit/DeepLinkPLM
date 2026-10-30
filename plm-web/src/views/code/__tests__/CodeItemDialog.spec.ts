import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus, { ElButton, ElRadioGroup, ElSelect } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CodeItemDialog from '../components/CodeItemDialog.vue'
import PhoneModelMultiSelect from '@/components/PhoneModelMultiSelect/index.vue'
import { useUserStore } from '@/stores/user'
const api = vi.hoisted(() => ({ getPhoneSelectionBrands: vi.fn(), getPhoneModels: vi.fn(), getPhoneModelOptions: vi.fn() }))
vi.mock('@/api/modules/phoneReference', () => api)
const pending = { id: 9, phoneModelId: 9, brandId: 6, brandName: 'Honor', modelName: 'Honor 600 Lite 5G', aliases: [], codeLinkStatus: 'unassigned', status: 'enabled' }
let w: VueWrapper | undefined
beforeEach(() => {
  vi.clearAllMocks(); setActivePinia(createPinia()); useUserStore().token = 'phone-code-form'
  api.getPhoneSelectionBrands.mockResolvedValue([{ id: 6, brandId: 6, name: 'Honor', status: 'enabled' }])
  api.getPhoneModels.mockResolvedValue({ content: [pending], totalElements: 1 }); api.getPhoneModelOptions.mockResolvedValue([pending])
})
afterEach(() => { w?.unmount(); w = undefined; document.body.innerHTML = '' })
async function start(props: Record<string, unknown> = {}) {
  w = mount(CodeItemDialog, { props: { modelValue: true, codeType: 'phone_model', ...props }, global: { plugins: [ElementPlus] } }); await flushPromises(); return w
}
async function value(label: string, text: string) { await w!.get(`input[aria-label="${label}"]`).setValue(text); await flushPromises() }
async function save() { await w!.get('[data-test="save-code-item"]').trigger('click'); await flushPromises() }
describe('one-save phone code registration', () => {
  it('prefills an existing identity and sends one assignment with its ID and the explicit factory short name', async () => {
    await start({ phone: pending }); expect(w!.getComponent(ElSelect).props('modelValue')).toBe(6)
    expect(w!.getComponent(ElSelect).props('disabled')).toBe(true); expect(w!.text()).toContain('Honor 600 Lite 5G')
    expect(w!.findComponent(PhoneModelMultiSelect).exists()).toBe(false); expect(w!.text()).not.toContain('排序')
    await value('四位手机编码', '0001'); await value('工厂简称', 'HR 600 LITE 5G'); await save()
    expect(w!.emitted('save')).toHaveLength(1)
    expect(w!.emitted('save')![0]![0]).toMatchObject({ codeValue: '0001', codeName: 'HR 600 LITE 5G', phoneRegistration: { brandId: 6, phoneModelId: 9 } })
    expect((w!.emitted('save')![0]![0] as Record<string, unknown>).phoneRegistration).not.toHaveProperty('modelName')
  })
  it('uses the shared pending-model picker and keeps a manually entered short name when another phone is selected', async () => {
    await start(); w!.getComponent(ElSelect).vm.$emit('update:modelValue', 6); await flushPromises()
    const picker = w!.getComponent(PhoneModelMultiSelect); expect(picker.props()).toMatchObject({ brandId: 6, unassignedCode: true, multiple: false })
    picker.vm.$emit('update:modelValue', [9]); picker.vm.$emit('selected', [pending]); await flushPromises()
    await value('工厂简称', 'HR MANUAL'); picker.vm.$emit('selected', [{ ...pending, modelName: 'Honor other full name' }]); await flushPromises()
    expect((w!.get('input[aria-label="工厂简称"]').element as HTMLInputElement).value).toBe('HR MANUAL')
    await value('四位手机编码', '0001'); await save(); expect(w!.emitted('save')).toHaveLength(1)
  })
  it('creates a genuinely new full name in the same form without inferring or allocating a code', async () => {
    await start(); w!.getComponent(ElSelect).vm.$emit('update:modelValue', 6); w!.getComponent(ElRadioGroup).vm.$emit('update:modelValue', 'new'); await flushPromises()
    await value('完整型号名称', 'Honor New Lite 5G'); await value('工厂简称', 'HR NEW LITE 5G')
    expect(w!.get('[data-test="save-code-item"]').attributes('disabled')).toBeDefined(); expect(w!.emitted('save')).toBeUndefined()
    await value('四位手机编码', '0002'); await save()
    expect(w!.emitted('save')![0]![0]).toMatchObject({ codeName: 'HR NEW LITE 5G', phoneRegistration: { brandId: 6, modelName: 'Honor New Lite 5G' } })
  })
  it('retains entered values on a brand-read failure and allows retry before submitting', async () => {
    api.getPhoneSelectionBrands.mockRejectedValueOnce(new Error('offline')); await start({ phone: pending })
    await value('四位手机编码', '0001'); expect(w!.text()).toContain('品牌读取失败')
    await save(); expect(w!.emitted('save')).toBeUndefined()
    await w!.findAllComponents(ElButton).find(b => b.text() === '重试')!.trigger('click'); await flushPromises(); await save()
    expect(w!.emitted('save')).toHaveLength(1)
  })
  it('keeps color maintenance independent, including its original sort order', async () => {
    await start({ codeType: 'color' }); await value('颜色编码值', '03'); await value('颜色原名称', 'Azul'); await save()
    expect(api.getPhoneSelectionBrands).not.toHaveBeenCalled(); expect(w!.text()).toContain('排序')
    expect(w!.emitted('save')![0]![0]).toMatchObject({ codeType: 'color', codeValue: '03', codeName: 'Azul', sortOrder: 0 })
    expect(w!.emitted('save')![0]![0]).not.toHaveProperty('phoneRegistration')
  })
})
