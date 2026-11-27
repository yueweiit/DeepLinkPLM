import { AxiosError, AxiosHeaders, CanceledError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'

import request, { authorizedPreviewOptions, clearSessionExpiry, prepareRequestContentType, registerSessionInvalidation, setSessionExpiry, SESSION_EXPIRY_STORAGE_KEY, unwrapResponse, type ApiResponse } from '../request'
const originalAdapter = request.defaults.adapter
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), info: vi.fn() } }))
let unregister: (() => void) | undefined
beforeEach(() => vi.clearAllMocks())
afterEach(() => { unregister?.(); unregister = undefined; request.defaults.adapter = originalAdapter; vi.useRealTimers(); localStorage.clear(); window.location.hash = '' })
function expired(token: string): AxiosResponse<ApiResponse<null>> {
  return { data: { code: 40101, message: '会话失效', data: null }, status: 200, statusText: 'OK', headers: new AxiosHeaders(), config: { headers: new AxiosHeaders({ Authorization: `Bearer ${token}` }) } }
}

function configWith(data: unknown): InternalAxiosRequestConfig {
  return {
    data,
    headers: new AxiosHeaders({ 'Content-Type': 'application/json' })
  } as InternalAxiosRequestConfig
}

describe('prepareRequestContentType', () => {
  it('removes fixed JSON content type for FormData', () => {
    const config = configWith(new FormData())

    prepareRequestContentType(config)

    expect(config.headers.has('Content-Type')).toBe(false)
  })

  it('keeps JSON content type for object payloads', () => {
    const config = configWith({ name: 'M4' })

    prepareRequestContentType(config)

    expect(config.headers.get('Content-Type')).toBe('application/json')
  })
})
describe('request session invalidation', () => {
  it('uses one registered invalidation for HTTP and typed business 401s, retaining the original login redirect', async () => {
    const handler = vi.fn(); unregister = registerSessionInvalidation(handler)
    localStorage.setItem('plm_token','expired'); window.location.hash = '/molds'
    await expect(request.get('/expired', { adapter: async config => { throw { config, response: { status:401, data:{message:'会话失效'} } } } })).rejects.toBeDefined()
    expect(handler).toHaveBeenCalledWith('expired'); expect(localStorage.getItem('plm_token')).toBeNull(); expect(window.location.hash).toBe('#/login?redirect=%2Fmolds')
    expect(ElMessage.info).toHaveBeenCalledTimes(1); expect(ElMessage.error).not.toHaveBeenCalled()
    localStorage.setItem('plm_token','expired-again'); window.location.hash = '/development-budgets'
    expect(() => unwrapResponse(expired('expired-again'))).toThrow('会话失效'); expect(handler).toHaveBeenCalledTimes(2)
    expect(window.location.hash).toBe('#/login?redirect=%2Fdevelopment-budgets')
    unregister(); localStorage.setItem('plm_token','disposed')
    expect(() => unwrapResponse(expired('disposed'))).toThrow(); expect(handler).toHaveBeenCalledTimes(2)
  })
  it('ignores late HTTP and typed 401s belonging to a previous Authorization token', async () => {
    const handler = vi.fn(); unregister = registerSessionInvalidation(handler)
    localStorage.setItem('plm_token','old'); window.location.hash = '/dashboard'
    await expect(request.get('/late', { adapter: async config => { localStorage.setItem('plm_token','new'); throw { config, response: { status:401, data:{code:40101,message:'旧会话失效'} } } } })).rejects.toBeDefined()
    expect(() => unwrapResponse(expired('old'))).toThrow()
    expect(localStorage.getItem('plm_token')).toBe('new'); expect(handler).not.toHaveBeenCalled(); expect(window.location.hash).toBe('#/dashboard')
    expect(ElMessage.error).not.toHaveBeenCalled(); expect(ElMessage.info).not.toHaveBeenCalled()
  })
  it('refreshes the local expiry deadline from the authenticated response header', async () => {
    const token='active-session',expiresAt=Date.now()+6*60*60*1000
    localStorage.setItem('plm_token',token)
    await request.get('/renewal-check',{adapter:async config=>({status:200,statusText:'OK',config,headers:{'x-plm-session-expires-at':String(expiresAt)},data:{code:0,data:null}})})
    expect(Number(localStorage.getItem(SESSION_EXPIRY_STORAGE_KEY))).toBe(expiresAt)
  })
  it('redirects once when the local session deadline passes', async () => {
    vi.useFakeTimers()
    const handler=vi.fn();unregister=registerSessionInvalidation(handler)
    localStorage.setItem('plm_token','expiring-session');window.location.hash='/projects?productId=17'
    request.defaults.adapter=async config=>{throw {config,response:{status:401,data:{code:40101,message:'会话失效'},headers:{},config}}}
    setSessionExpiry('expiring-session',Date.now()+1000)
    await vi.advanceTimersByTimeAsync(1000)
    expect(handler).toHaveBeenCalledWith('expiring-session')
    expect(localStorage.getItem('plm_token')).toBeNull();expect(localStorage.getItem(SESSION_EXPIRY_STORAGE_KEY)).toBeNull()
    expect(window.location.hash).toBe('#/login?redirect=%2Fprojects%3FproductId%3D17')
    expect(ElMessage.info).toHaveBeenCalledTimes(1);expect(ElMessage.error).not.toHaveBeenCalled()
  })
  it('checks the server before expiring locally when an earlier request renewed the session', async () => {
    vi.useFakeTimers()
    localStorage.setItem('plm_token','in-flight-session')
    const renewedUntil=Date.now()+60_000
    request.defaults.adapter=async config=>{
      expect(config.headers.get('X-PLM-Session-Check')).toBe('true')
      return {status:200,statusText:'OK',config,headers:{'x-plm-session-expires-at':String(renewedUntil)},data:{code:0,data:{userId:1}}}
    }
    setSessionExpiry('in-flight-session',Date.now()+1000)

    await vi.advanceTimersByTimeAsync(1000)

    expect(localStorage.getItem('plm_token')).toBe('in-flight-session')
    expect(Number(localStorage.getItem(SESSION_EXPIRY_STORAGE_KEY))).toBe(renewedUntil)
    expect(ElMessage.info).not.toHaveBeenCalled()
    clearSessionExpiry('in-flight-session')
  })
  it('keeps the local session during a network outage and retries the passive check', async () => {
    vi.useFakeTimers()
    localStorage.setItem('plm_token','offline-at-expiry-session')
    let checks=0
    const renewedUntil=Date.now()+60_000
    request.defaults.adapter=async config=>{
      checks++
      if(checks===1) throw new AxiosError('Network Error','ERR_NETWORK',config)
      return {status:200,statusText:'OK',config,headers:{'x-plm-session-expires-at':String(renewedUntil)},data:{code:0,data:{userId:1}}}
    }
    setSessionExpiry('offline-at-expiry-session',Date.now()+1000)

    await vi.advanceTimersByTimeAsync(1000)
    expect(checks).toBe(1)
    expect(localStorage.getItem('plm_token')).toBe('offline-at-expiry-session')
    expect(ElMessage.info).not.toHaveBeenCalled()
    expect(ElMessage.error).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(15_000)
    expect(checks).toBe(2)
    expect(Number(localStorage.getItem(SESSION_EXPIRY_STORAGE_KEY))).toBe(renewedUntil)
    expect(ElMessage.info).not.toHaveBeenCalled()
    clearSessionExpiry('offline-at-expiry-session')
  })
})
describe('inline media errors', () => {
  it('rejects an old media job before axios can send it under the replacement login', async () => {
    localStorage.setItem('plm_token','old-preview-fixture')
    const options=authorizedPreviewOptions(), adapter=vi.fn()
    localStorage.setItem('plm_token','new-preview-fixture')
    await expect(request.get('/private-photo',{...options,adapter})).rejects.toBeInstanceOf(CanceledError)
    expect(adapter).not.toHaveBeenCalled();expect(ElMessage.error).not.toHaveBeenCalled();expect(localStorage.getItem('plm_token')).toBe('new-preview-fixture')
  })
  it('rejects cancelled work without showing a misleading global network error', async () => {
    await expect(request.get('/cancelled', { adapter: async config => { const error=new CanceledError('cancelled');error.config=config;throw error } })).rejects.toBeInstanceOf(CanceledError)
    expect(ElMessage.error).not.toHaveBeenCalled()
  })
  it('keeps ordinary request errors visible while explicitly inline preview errors still reject', async () => {
    const adapter = async (config: InternalAxiosRequestConfig) => { throw { config, response: {status:503,data:{message:'服务暂不可用'}} } }
    await expect(request.get('/inline-image',{suppressErrorToast:true,adapter})).rejects.toBeDefined()
    expect(ElMessage.error).not.toHaveBeenCalled()
    await expect(request.get('/ordinary-business',{adapter})).rejects.toBeDefined()
    expect(ElMessage.error).toHaveBeenCalledWith('服务暂不可用')
  })
  it('never suppresses HTTP 401 session invalidation or its login redirect for inline previews', async () => {
    const handler=vi.fn();unregister=registerSessionInvalidation(handler)
    localStorage.setItem('plm_token','expired-preview-fixture');window.location.hash='/partner-selections?id=2'
    await expect(request.get('/inline-image',{suppressErrorToast:true,adapter:async config=>{throw {config,response:{status:401,data:{message:'会话失效'}}}}})).rejects.toBeDefined()
    expect(handler).toHaveBeenCalledWith('expired-preview-fixture');expect(localStorage.getItem('plm_token')).toBeNull();expect(window.location.hash).toContain('#/login?redirect=')
  })
})
