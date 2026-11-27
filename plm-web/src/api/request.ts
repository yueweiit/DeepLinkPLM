import axios, { type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { t as uiText } from '@/locales'

const TOKEN_STORAGE_KEY = 'plm_token'
export const SESSION_EXPIRY_STORAGE_KEY = 'plm_session_expires_at'
const SESSION_EXPIRY_HEADER = 'x-plm-session-expires-at'
const MAX_TIMER_DELAY_MS = 2_147_000_000
const SESSION_EXPIRY_RETRY_MS = 15_000

declare module 'axios' {
  interface AxiosRequestConfig<D = any> {
    /** Explicit media-viewer errors are rendered inline; authentication still invalidates. */
    suppressErrorToast?: boolean
    /** Explicit logout handles expired sessions as a successful local sign-out. */
    suppressSessionExpiredNotice?: boolean
    /** Preserve the captured bearer when logout races with a later login. */
    isLogoutRequest?: boolean
    /** Check session validity without counting the check as activity. */
    isSessionCheck?: boolean
    /** Media jobs must still belong to the login that requested them at dispatch time. */
    isAuthSessionCurrent?: () => boolean
  }
}

const request = axios.create({
  baseURL: `${import.meta.env.VITE_API_BASE_URL || ''}/api/v1`,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

let sessionInvalidation: ((expiredToken: string | null) => void) | undefined
let sessionExpiryTimer: number | undefined
let sessionExpiryCheckToken: string | undefined

/** The store owns session state; this layer never imports Pinia or creates a login flow. */
export function registerSessionInvalidation(handler: (expiredToken: string | null) => void) {
  sessionInvalidation = handler
  const token = localStorage.getItem(TOKEN_STORAGE_KEY)
  const expiresAt = Number(localStorage.getItem(SESSION_EXPIRY_STORAGE_KEY))
  if (token && Number.isFinite(expiresAt) && expiresAt > 0) scheduleSessionExpiry(token, expiresAt)
  return () => { if (sessionInvalidation === handler) sessionInvalidation = undefined }
}

function clearSessionExpiryTimer() {
  if (sessionExpiryTimer !== undefined) window.clearTimeout(sessionExpiryTimer)
  sessionExpiryTimer = undefined
}

export function clearSessionExpiry(expectedToken?: string | null) {
  if (expectedToken && localStorage.getItem(TOKEN_STORAGE_KEY) !== expectedToken) return
  clearSessionExpiryTimer()
  localStorage.removeItem(SESSION_EXPIRY_STORAGE_KEY)
}

function currentRouteForLogin() {
  const current = window.location.hash.replace(/^#/, '') || '/'
  if (!current.startsWith('/login')) window.location.hash = `/login?redirect=${encodeURIComponent(current)}`
}

function endSession(token: string, showNotice: boolean) {
  if (!token || localStorage.getItem(TOKEN_STORAGE_KEY) !== token) return false
  clearSessionExpiryTimer()
  sessionInvalidation?.(token)
  if (localStorage.getItem(TOKEN_STORAGE_KEY) === token) localStorage.removeItem(TOKEN_STORAGE_KEY)
  localStorage.removeItem(SESSION_EXPIRY_STORAGE_KEY)
  currentRouteForLogin()
  if (showNotice) ElMessage.info(uiText('登录状态已过期，请重新登录'))
  return true
}

function scheduleSessionExpiry(token: string, expiresAt: number) {
  if (localStorage.getItem(TOKEN_STORAGE_KEY) !== token) return
  clearSessionExpiryTimer()
  const remaining = expiresAt - Date.now()
  if (remaining <= 0) {
    sessionExpiryTimer = window.setTimeout(() => { void verifySessionAtExpiry(token) }, 0)
    return
  }
  sessionExpiryTimer = window.setTimeout(() => {
    const latest = Number(localStorage.getItem(SESSION_EXPIRY_STORAGE_KEY))
    if (latest > Date.now()) scheduleSessionExpiry(token, latest)
    else void verifySessionAtExpiry(token)
  }, Math.min(remaining, MAX_TIMER_DELAY_MS))
}

async function verifySessionAtExpiry(token: string) {
  if (localStorage.getItem(TOKEN_STORAGE_KEY) !== token) return
  const currentExpiry = Number(localStorage.getItem(SESSION_EXPIRY_STORAGE_KEY))
  if (currentExpiry > Date.now()) {
    scheduleSessionExpiry(token, currentExpiry)
    return
  }
  if (sessionExpiryCheckToken === token) return

  sessionExpiryCheckToken = token
  try {
    const response = await request.get('/auth/profile', { suppressErrorToast: true, isSessionCheck: true })
    unwrapResponse(response)
    if (localStorage.getItem(TOKEN_STORAGE_KEY) !== token) return
    const refreshedExpiry = Number(localStorage.getItem(SESSION_EXPIRY_STORAGE_KEY))
    if (refreshedExpiry > Date.now()) scheduleSessionExpiry(token, refreshedExpiry)
    else scheduleSessionExpiryRetry(token)
  } catch {
    if (localStorage.getItem(TOKEN_STORAGE_KEY) !== token) return
    const refreshedExpiry = Number(localStorage.getItem(SESSION_EXPIRY_STORAGE_KEY))
    if (refreshedExpiry > Date.now()) scheduleSessionExpiry(token, refreshedExpiry)
    else scheduleSessionExpiryRetry(token)
  } finally {
    if (sessionExpiryCheckToken === token) sessionExpiryCheckToken = undefined
  }
}

function scheduleSessionExpiryRetry(token: string) {
  if (localStorage.getItem(TOKEN_STORAGE_KEY) !== token) return
  clearSessionExpiryTimer()
  sessionExpiryTimer = window.setTimeout(() => { void verifySessionAtExpiry(token) }, SESSION_EXPIRY_RETRY_MS)
}

export function setSessionExpiry(token: string, expiresAt: number) {
  if (!token || !Number.isFinite(expiresAt) || localStorage.getItem(TOKEN_STORAGE_KEY) !== token) return
  localStorage.setItem(SESSION_EXPIRY_STORAGE_KEY, String(Math.floor(expiresAt)))
  scheduleSessionExpiry(token, expiresAt)
}

function tokenSentBy(config?: InternalAxiosRequestConfig) {
  const authorization = config?.headers?.get?.('Authorization') ?? config?.headers?.Authorization
  return typeof authorization === 'string' ? /^Bearer\s+(.+)$/i.exec(authorization)?.[1] ?? null : null
}

function updateSessionExpiry(response: Pick<AxiosResponse, 'config' | 'headers'>) {
  const token = tokenSentBy(response.config)
  const raw = response.headers?.[SESSION_EXPIRY_HEADER]
  const expiresAt = Number(raw)
  if (token && token === localStorage.getItem(TOKEN_STORAGE_KEY) && Number.isFinite(expiresAt) && expiresAt > 0) {
    setSessionExpiry(token, expiresAt)
  }
}

function handleUnauthorized(config?: InternalAxiosRequestConfig) {
  const sentToken = tokenSentBy(config)
  if (!sentToken) return false
  if (sentToken !== localStorage.getItem(TOKEN_STORAGE_KEY)) return true
  endSession(sentToken, !config?.suppressSessionExpiredNotice)
  return true
}

if (typeof window !== 'undefined') {
  window.addEventListener('focus', () => {
    const token = localStorage.getItem(TOKEN_STORAGE_KEY)
    const expiresAt = Number(localStorage.getItem(SESSION_EXPIRY_STORAGE_KEY))
    if (token && expiresAt > 0 && expiresAt <= Date.now()) void verifySessionAtExpiry(token)
  })
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') return
    const token = localStorage.getItem(TOKEN_STORAGE_KEY)
    const expiresAt = Number(localStorage.getItem(SESSION_EXPIRY_STORAGE_KEY))
    if (token && expiresAt > 0 && expiresAt <= Date.now()) void verifySessionAtExpiry(token)
  })
  window.addEventListener('storage', (event) => {
    if (event.key === TOKEN_STORAGE_KEY && event.newValue === null && event.oldValue && localStorage.getItem(TOKEN_STORAGE_KEY) === null) {
      clearSessionExpiryTimer()
      sessionInvalidation?.(event.oldValue)
      localStorage.removeItem(SESSION_EXPIRY_STORAGE_KEY)
      currentRouteForLogin()
      return
    }
    if (event.key === SESSION_EXPIRY_STORAGE_KEY) {
      const token = localStorage.getItem(TOKEN_STORAGE_KEY)
      const expiresAt = Number(event.newValue)
      if (!event.newValue) clearSessionExpiryTimer()
      else if (token && expiresAt > 0) scheduleSessionExpiry(token, expiresAt)
    }
  })
}

function createRequestId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }

  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export function prepareRequestContentType(config: InternalAxiosRequestConfig) {
  if (typeof FormData !== 'undefined' && config.data instanceof FormData) {
    config.headers.delete('Content-Type')
  }
  return config
}

request.interceptors.request.use((config) => {
  if (config.isAuthSessionCurrent && !config.isAuthSessionCurrent()) {
    const cancelled = new axios.CanceledError('图片会话已变化')
    cancelled.config = config
    throw cancelled
  }
  prepareRequestContentType(config)
  const token = config.isLogoutRequest ? null : localStorage.getItem(TOKEN_STORAGE_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  if (config.isSessionCheck) config.headers['X-PLM-Session-Check'] = 'true'
  if (config.url?.startsWith('/product-research')) config.headers['X-PLM-Research-Model'] = 'eight_step'
  config.headers['X-Request-Id'] = createRequestId()
  return config
})

request.interceptors.response.use(
  (response) => { updateSessionExpiry(response); return response },
  (error) => {
    if (error.response) updateSessionExpiry(error.response)
    if (error.response?.status === 401 || error.response?.data?.code === 40101) {
      if (handleUnauthorized(error.config ?? error.response?.config)) return Promise.reject(error)
    }
    // Image viewers cancel work when hidden or replaced; cancellation is not a network failure.
    if (!axios.isCancel(error) && !error.config?.suppressErrorToast) ElMessage.error(error.response?.data?.message || '网络请求失败')
    return Promise.reject(error)
  }
)

export function mockResolve<T>(factory: () => T, delay = 180): Promise<T> {
  return new Promise((resolve, reject) => {
    window.setTimeout(() => {
      try {
        resolve(factory())
      } catch (error) {
        reject(error)
      }
    }, delay)
  })
}

/** Keep secrets inside a closure, not in observable resource keys or UI state. */
export function authorizedPreviewOptions(options: {signal?: AbortSignal} = {}) {
  const session = localStorage.getItem('plm_token')
  return {responseType: 'blob' as const, timeout: 120000, suppressErrorToast: true,
    isAuthSessionCurrent: () => localStorage.getItem('plm_token') === session, ...options}
}

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  requestId?: string
  timestamp?: string
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export function unwrapResponse<T>(response: AxiosResponse<ApiResponse<T>>): T {
  const body = response.data
  if (body.code !== 0) {
    if (body.code === 40101) {
      handleUnauthorized(response.config)
    }
    throw new Error(body.message || '接口请求失败')
  }
  return body.data
}

export function unwrapPage<T>(response: AxiosResponse<ApiResponse<PageResponse<T>>>): PageResponse<T> {
  return unwrapResponse(response)
}

export default request
