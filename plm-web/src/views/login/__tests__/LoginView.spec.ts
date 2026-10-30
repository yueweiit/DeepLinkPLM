import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { setLanguage } from '@/locales'
vi.mock('@/stores/user',()=>({useUserStore:()=>({login:vi.fn(),loginDingTalk:vi.fn(),hasPermission:()=>false,profile:null,changeLanguage:async(language:'zh-CN'|'es')=>(await import('@/locales')).setLanguage(language)})}))
vi.mock('@/api/modules/pilot',()=>({getPilotSsoStatus:vi.fn().mockResolvedValue({enabled:false,corpId:'',message:'未启用钉钉免登'})}))
let wrapper:VueWrapper|undefined
afterEach(()=>{wrapper?.unmount();vi.unstubAllEnvs();setLanguage('zh-CN')})
describe('production login credential boundary',()=>{
  it('switches the actual login form to Spanish without remounting or clearing typed credentials',async()=>{
    vi.stubEnv('DEV',false);setLanguage('zh-CN')
    const LoginView=(await import('../LoginView.vue')).default
    const router=createRouter({history:createMemoryHistory(),routes:[{path:'/login',component:LoginView}]})
    await router.push('/login');await router.isReady()
    wrapper=mount(LoginView,{global:{plugins:[router,ElementPlus]}});await flushPromises()
    const inputs=wrapper.findAll('input'),username=inputs[0]!.element,password=inputs[1]!.element
    await inputs[0]!.setValue('unsaved-user');await inputs[1]!.setValue('test-only-not-submitted')
    await wrapper.get('select.language-switcher').setValue('es');await flushPromises()
    expect(wrapper.text()).toContain('Usuario');expect(wrapper.text()).toContain('Contraseña')
    expect(wrapper.findAll('input')[0]!.element).toBe(username);expect(wrapper.findAll('input')[1]!.element).toBe(password)
    expect((username as HTMLInputElement).value).toBe('unsaved-user');expect((password as HTMLInputElement).value).toBe('test-only-not-submitted')
    await wrapper.get('select.language-switcher').setValue('zh-CN');await flushPromises()
    expect(wrapper.text()).toContain('用户名');expect((username as HTMLInputElement).value).toBe('unsaved-user')
  })
  it('starts empty and hides legacy test credentials when DEV is false',async()=>{
    vi.stubEnv('DEV',false)
    const LoginView=(await import('../LoginView.vue')).default
    const router=createRouter({history:createMemoryHistory(),routes:[{path:'/login',component:LoginView}]})
    await router.push('/login');await router.isReady()
    wrapper=mount(LoginView,{global:{plugins:[router,ElementPlus]}});await flushPromises()
    expect(wrapper.findAll('input').map(input=>(input.element as HTMLInputElement).value)).toEqual(['',''])
    expect(wrapper.text()).not.toContain('后端 M1 测试账号');expect(wrapper.text()).not.toContain('engineer01');expect(wrapper.text()).not.toContain('plm123456')
    expect(wrapper.text()).toContain('钉钉轻应用免登')
  })
})
