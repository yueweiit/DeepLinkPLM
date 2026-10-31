import { defineStore } from 'pinia'
import { computed, onScopeDispose, ref } from 'vue'

import { getProfileByToken, loginByPassword, loginByDingTalkCode, logout as logoutApi, saveOwnLanguage } from '@/api/modules/auth'
import { normalizeLanguage, setLanguage, type Language } from '@/locales/language'
import type { UserProfile } from '@/types/common'
import { registerSessionInvalidation } from '@/api/request'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('plm_token') || '')
  const profile = ref<UserProfile | null>(null)
  const permissions = ref<string[]>([])

  const isLoggedIn = computed(() => Boolean(token.value))

  function clearSession(expectedToken?: string | null) {
    if (expectedToken !== undefined && token.value !== (expectedToken || '')) return
    token.value = ''
    profile.value = null
    permissions.value = []
    localStorage.removeItem('plm_token')
  }
  onScopeDispose(registerSessionInvalidation(clearSession))

  function setSession(payload: { token: string; profile: UserProfile; permissions: string[] }) {
    token.value = payload.token
    profile.value = payload.profile
    permissions.value = payload.permissions
    setLanguage(normalizeLanguage(payload.profile.preferredLanguage))
    localStorage.setItem('plm_token', payload.token)
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
  }

  async function logout() {
    const loggingOutToken = token.value
    try {
      if (token.value) {
        await logoutApi()
      }
    } finally {
      clearSession(loggingOutToken)
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
