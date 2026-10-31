import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios'
import { createPinia, disposePinia, setActivePinia, type Pinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { Router } from 'vue-router'

let pinia:Pinia,router:Router|undefined,wrapper:VueWrapper|undefined
const fixtureToken='isolated-recovery-session'
beforeEach(()=>{vi.resetModules();localStorage.clear();window.location.hash='';pinia=createPinia();setActivePinia(pinia)})
afterEach(()=>{wrapper?.unmount();wrapper=undefined;router?.options.history.destroy();router=undefined;disposePinia(pinia);localStorage.clear();window.location.hash='';document.body.innerHTML='';vi.restoreAllMocks()})

async function start(failure:number|'network'|'typed401'|undefined,permissions=['collaboration:view'],roleCode='domestic'){
  localStorage.setItem('plm_token',fixtureToken)
  const user=(await import('@/stores/user')).useUserStore()
  const request=(await import('@/api/request')).default
  let reads=0
  request.defaults.adapter=async(config:InternalAxiosRequestConfig)=>{
    expect(config.url).toBe('/auth/profile');reads++
    if(reads===1&&failure==='network')throw new AxiosError('Network Error','ERR_NETWORK',config)
    if(reads===1&&typeof failure==='number'){
      const response={status:failure,statusText:'Unavailable',config,headers:new AxiosHeaders(),data:{code:failure===401?40101:50301,message:'隔离接口响应',data:null}}
      throw new AxiosError('Request failed','ERR_BAD_RESPONSE',config,undefined,response)
    }
    return {status:200,statusText:'OK',config,headers:new AxiosHeaders(),data:reads===1&&failure==='typed401'?{code:40101,message:'会话失效',data:null}:{code:0,message:'',data:{userId:7,username:'isolated-user',displayName:'隔离普通账号',allPermissions:false,departmentName:'工程',permissions,roleCode}}}
  }
  router=(await import('../index')).default
  return {user,reads:()=>reads}
}

describe('normal session restoration and recovery routing',()=>{
  it.each([502,503,'network'] as const)('keeps a temporary %s failure out of login and retries the original route with the same token',async failure=>{
    const {user,reads}=await start(failure)
    const target='/production-totals?view=model&keyword=0064'
    await router!.push(target);await router!.isReady()
    expect(router!.currentRoute.value.path).toBe('/session-recovery')
    expect(router!.currentRoute.value.query.redirect).toBe(target);expect(reads()).toBe(1)
    expect(user.token).toBe(fixtureToken);expect(localStorage.getItem('plm_token')).toBe(fixtureToken)
    expect(user.profile).toBeNull();expect(user.permissions).toEqual([])
    const Recovery=(await import('@/views/error/SessionRecoveryView.vue')).default
    wrapper=mount(Recovery,{global:{plugins:[pinia,router!,ElementPlus]}})
    expect(wrapper.text()).toContain('暂时无法连接系统，登录资料已保留')
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    const recovered=new Promise<void>(resolve=>{const stop=router!.afterEach(to=>{if(to.fullPath===target){stop();resolve()}})})
    await wrapper.get('button').trigger('click');await recovered;await flushPromises()
    expect(router!.currentRoute.value.fullPath).toBe(target);expect(reads()).toBe(2)
    expect(user.profile?.userName).toBe('隔离普通账号');expect(user.token).toBe(fixtureToken)
  })
  it.each([401,'typed401'] as const)('keeps genuine %s invalidation on the existing login route',async failure=>{
    const {user,reads}=await start(failure)
    await router!.push('/production-totals');await router!.isReady()
    expect(router!.currentRoute.value.path).toBe('/login');expect(user.token).toBe('')
    expect(user.profile).toBeNull();expect(localStorage.getItem('plm_token')).toBeNull();expect(reads()).toBe(1)
  })
  it('requires the existing login when a recovery deep link has no token',async()=>{
    const {user,reads}=await start(undefined);user.clearSession()
    await router!.push('/session-recovery?redirect=%2Fproduction-totals%3Fview%3Dmodel');await router!.isReady()
    expect(router!.currentRoute.value.path).toBe('/login')
    expect(router!.currentRoute.value.query.redirect).toBe('/production-totals?view=model');expect(reads()).toBe(0)
  })
  it('keeps an ordinary logged-out login route without attempting profile recovery',async()=>{
    const {user,reads}=await start(undefined);user.clearSession()
    await router!.push('/login');await router!.isReady()
    expect(router!.currentRoute.value.path).toBe('/login');expect(reads()).toBe(0)
  })
  it('keeps the existing default home for a normally restored login without entering recovery',async()=>{
    const {user,reads}=await start(undefined)
    await router!.push('/login');await router!.isReady()
    expect(router!.currentRoute.value.path).toBe('/market-watch');expect(reads()).toBe(1)
    expect(user.profile?.userName).toBe('隔离普通账号');expect(user.token).toBe(fixtureToken)
  })
  it('rechecks existing page permissions after recovery instead of granting the requested page',async()=>{
    const {user,reads}=await start(503,['partner:view'])
    await router!.push('/production-totals');await router!.isReady()
    expect(router!.currentRoute.value.path).toBe('/session-recovery')
    await router!.replace(String(router!.currentRoute.value.query.redirect))
    expect(router!.currentRoute.value.path).toBe('/403');expect(user.permissions).toEqual(['partner:view']);expect(reads()).toBe(2)
  })
  it('retains the external-role destination rule after the same session is restored',async()=>{
    const {reads}=await start(502,['pilot:tasks'],'mexico')
    await router!.push('/production-totals');await router!.isReady()
    await router!.replace(String(router!.currentRoute.value.query.redirect))
    expect(router!.currentRoute.value.path).toBe('/pilot/tasks');expect(reads()).toBe(2)
  })
})
