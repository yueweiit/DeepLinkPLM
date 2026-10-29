import { AxiosHeaders, CanceledError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'

import request, { authorizedPreviewOptions, prepareRequestContentType, registerSessionInvalidation, unwrapResponse, type ApiResponse } from '../request'
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))
let unregister: (() => void) | undefined
beforeEach(() => vi.clearAllMocks())
afterEach(() => { unregister?.(); unregister = undefined; localStorage.clear(); window.location.hash = '' })
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
