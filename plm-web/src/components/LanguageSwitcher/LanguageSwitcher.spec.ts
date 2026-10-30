import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import LanguageSwitcher from './index.vue'
import { useUserStore } from '@/stores/user'
import { currentLanguage, setLanguage } from '@/locales'
const api=vi.hoisted(()=>({saveOwnLanguage:vi.fn()}))
vi.mock('@/api/modules/auth',()=>({...api,getProfileByToken:vi.fn(),loginByPassword:vi.fn(),loginByDingTalkCode:vi.fn(),logout:vi.fn()}))
vi.mock('element-plus',()=>({ElMessage:{error:vi.fn()}}))
afterEach(()=>{setLanguage('zh-CN');vi.clearAllMocks()})
describe('language switcher',()=>{
 it('changes the login display locally before a user is signed in',async()=>{
  const pinia=createPinia();setActivePinia(pinia);const view=mount(LanguageSwitcher,{global:{plugins:[pinia]}})
  await view.get('select').setValue('es');await flushPromises()
  expect(currentLanguage.value).toBe('es');expect(api.saveOwnLanguage).not.toHaveBeenCalled();view.unmount()
 })
 it('saves only the signed-in preference and keeps the previous display if saving fails',async()=>{
  const pinia=createPinia();setActivePinia(pinia);const user=useUserStore();user.profile={userId:2,userName:'Engineer',roleName:'工程',department:'Engineering',preferredLanguage:'zh-CN'}
  api.saveOwnLanguage.mockRejectedValueOnce(new Error('conflict'))
  const view=mount(LanguageSwitcher,{global:{plugins:[pinia]}});await view.get('select').setValue('es');await flushPromises()
  expect(api.saveOwnLanguage).toHaveBeenCalledWith('es','zh-CN');expect(currentLanguage.value).toBe('zh-CN');expect(user.profile.preferredLanguage).toBe('zh-CN')
  api.saveOwnLanguage.mockResolvedValueOnce({preferredLanguage:'es'});await view.get('select').setValue('es');await flushPromises()
  expect(currentLanguage.value).toBe('es');expect(user.profile.preferredLanguage).toBe('es');view.unmount()
 })
})
