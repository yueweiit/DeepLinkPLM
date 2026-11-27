import { defineStore } from 'pinia'
import { computed, onScopeDispose, ref } from 'vue'

import { getProfileByToken, loginByPassword, loginByDingTalkCode, logout as logoutApi, saveOwnLanguage } from '@/api/modules/auth'
import { normalizeLanguage, setLanguage, type Language } from '@/locales/language'
import type { UserProfile } from '@/types/common'
import { clearSessionExpiry, registerSessionInvalidation, setSessionExpiry, SESSION_EXPIRY_STORAGE_KEY } from '@/api/request'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('plm_token') || '')
  const profile = ref<UserProfile | null>(null)
  const permissions = ref<string[]>([])

  const isLoggedIn = computed(() => Boolean(token.value))

  function clearSession(expectedToken?: string | null) {
    if (expectedToken !== undefined && token.value !== (expectedToken || '')) return
    const storedToken = localStorage.getItem('plm_token')
    if (expectedToken && storedToken && storedToken !== expectedToken) return
    token.value = ''
    profile.value = null
    permissions.value = []
    if (!expectedToken || !storedToken || storedToken === expectedToken) {
      clearSessionExpiry(expectedToken)
      localStorage.removeItem('plm_token')
    }
  }
  const unregisterSessionInvalidation = registerSessionInvalidation(clearSession)
  onScopeDispose(() => {
    unregisterSessionInvalidation()
    clearSessionExpiry(token.value)
  })

  function setSession(payload: { token: string; profile: UserProfile; permissions: string[]; expiresInSeconds?: number }) {
    token.value = payload.token
    profile.value = payload.profile
    permissions.value = payload.permissions
    setLanguage(normalizeLanguage(payload.profile.preferredLanguage))
    localStorage.setItem('plm_token', payload.token)
    const expiresInSeconds = payload.expiresInSeconds || 24 * 60 * 60
    setSessionExpiry(payload.token, Date.now() + expiresInSeconds * 1000)
  }

  async function login(payload: { username: string; password: string }) {
    const session = await loginByPassword(payload)
    setSession(session)
    return session
  }

  async function loginDingTalk(code: string) {
    const session = await loginByDingTalkCode(code)
    setSession(session)
    return session
  }

  async function restore() {
    if (!token.value) {
      return
    }

    const restoringToken = token.value
    // Real 401s invalidate through the shared request handler; connection failures retain this session.
    const session = await getProfileByToken(restoringToken)
    if (token.value !== restoringToken) return
    profile.value = session.profile
    permissions.value = session.permissions
    setLanguage(normalizeLanguage(session.profile.preferredLanguage))
    if (!localStorage.getItem(SESSION_EXPIRY_STORAGE_KEY)) setSessionExpiry(restoringToken, Date.now() + 24 * 60 * 60 * 1000)
  }

  async function logout(): Promise<{ serverRevoked: boolean }> {
    const loggingOutToken = token.value
    clearSession(loggingOutToken)
    if (!loggingOutToken) return { serverRevoked: true }
    try {
      await logoutApi(loggingOutToken)
      return { serverRevoked: true }
    } catch (error) {
      const failure = error as { response?: { status?: number; data?: { code?: number } } }
      if (failure.response?.status === 401 || failure.response?.data?.code === 40101) return { serverRevoked: true }
      return { serverRevoked: false }
    }
  }

  async function changeLanguage(language: Language) {
    if (!profile.value) { setLanguage(language); return }
    const changingToken = token.value
    const result = await saveOwnLanguage(language, normalizeLanguage(profile.value.preferredLanguage))
    if (changingToken !== token.value || !profile.value) return
    profile.value = {...profile.value, preferredLanguage: result.preferredLanguage}
    setLanguage(result.preferredLanguage)
  }

  function hasPermission(permission?: string) {
    if (!permission) {
      return true
    }
    return permissions.value.includes('*') || permissions.value.includes(permission)
  }

  return {
    token,
    profile,
    permissions,
    isLoggedIn,
    login,
    loginDingTalk,
    restore,
    logout,
    clearSession,
    hasPermission,
    changeLanguage
  }
})
