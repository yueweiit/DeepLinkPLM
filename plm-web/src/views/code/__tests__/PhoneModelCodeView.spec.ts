import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ getMoldOptions: vi.fn() }))
vi.mock('@/api/modules/molds', () => api)
vi.mock('@/views/mold/components/PhoneReferenceManager.vue', () => ({default:{
  props:['modelValue','inline','editable','initialTab','keyword','phoneModelId','coverageOnOpen','reviewKind','reviewState'],emits:['tabChange'],template:'<section data-test="existing-phone-reference" />'
}}))
import PhoneModelCodeView from '../PhoneModelCodeView.vue'
import PhoneReferenceManager from '@/views/mold/components/PhoneReferenceManager.vue'
import { useUserStore } from '@/stores/user'
let wrapper: VueWrapper | undefined
beforeEach(() => {vi.clearAllMocks();localStorage.clear();api.getMoldOptions.mockResolvedValue({writableGroups:['engineering']})})
afterEach(() => {wrapper?.unmount();wrapper=undefined})
async function start(path='/phone-model-codes') {
  const pinia=createPinia();setActivePinia(pinia);useUserStore().token='phone-code-page'
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/phone-model-codes',component:PhoneModelCodeView},{path:'/molds',component:{template:'<div />'}}]})
  await router.push(path);await router.isReady()
  wrapper=mount(PhoneModelCodeView,{global:{plugins:[pinia,router,ElementPlus]}});await flushPromises()
  return router
}
describe('independent phone-code module',()=>{
  it('routes intake as a tab of the existing phone page without introducing a separate menu or losing the query',async()=>{const router=await start('/phone-model-codes?tab=intake&keyword=真实型号');expect(wrapper!.getComponent(PhoneReferenceManager).props()).toMatchObject({initialTab:'intake',keyword:'真实型号',editable:true});wrapper!.getComponent(PhoneReferenceManager).vm.$emit('tabChange','issues');await flushPromises();expect(router.currentRoute.value.query).toEqual({tab:'issues',keyword:'真实型号'})})
  it('keeps the original URL as a page and reuses the common inline manager with authoritative engineering permission',async()=>{
    const router=await start()
    expect(router.currentRoute.value.path).toBe('/phone-model-codes');expect(wrapper!.get('h2').text()).toBe('手机编码')
    expect(wrapper!.getComponent(PhoneReferenceManager).props()).toMatchObject({inline:true,modelValue:true,initialTab:'groups',editable:true})
    expect(api.getMoldOptions).toHaveBeenCalledTimes(1)
  })
  it('preserves exact history and phone deep links and updates the same route when the user changes tabs',async()=>{
    const router=await start('/phone-model-codes?tab=review&kind=name_many_codes&reviewState=all&keyword=0064')
    const manager=wrapper!.getComponent(PhoneReferenceManager)
    expect(manager.props()).toMatchObject({initialTab:'review',reviewKind:'name_many_codes',reviewState:'',keyword:'0064'})
    await router.push('/phone-model-codes?phoneModelId=548&panel=coverage');await flushPromises()
    expect(manager.props()).toMatchObject({initialTab:'phones',phoneModelId:548,coverageOnOpen:true})
    manager.vm.$emit('tabChange','brands');await flushPromises()
    expect(router.currentRoute.value.query).toEqual({tab:'brands'})
    expect(manager.props('initialTab')).toBe('brands')
  })
  it('accepts original-code lookup links while keeping users without engineering assignment read-only',async()=>{
    api.getMoldOptions.mockResolvedValue({writableGroups:['market']})
    await start('/phone-model-codes?tab=codes&keyword=0001')
    expect(wrapper!.getComponent(PhoneReferenceManager).props()).toMatchObject({initialTab:'groups',keyword:'0001',editable:false})
    expect(wrapper!.text()).toContain('资料只读')
  })
  it('does not restore prior engineering permission when the account changes during an options request',async()=>{
    let finish!:(value:unknown)=>void
    api.getMoldOptions.mockReturnValue(new Promise(resolve=>{finish=resolve}))
    await start();useUserStore().token='another-account';await flushPromises()
    finish({writableGroups:['engineering']});await flushPromises()
    expect(wrapper!.getComponent(PhoneReferenceManager).props('editable')).toBe(false)
  })
})
