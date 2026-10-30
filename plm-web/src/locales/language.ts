import { ref, readonly } from 'vue'

export type Language = 'zh-CN' | 'es'
export const languages = [{ value: 'zh-CN', label: '中文' }, { value: 'es', label: 'Español' }] as const
export function normalizeLanguage(value: unknown): Language { return value === 'es' ? 'es' : 'zh-CN' }

const selected = ref<Language>(normalizeLanguage(typeof window === 'undefined' ? null : localStorage.getItem('plm_language')))
export const currentLanguage = readonly(selected)

export function setLanguage(value: Language) {
  selected.value = value
  if (typeof window !== 'undefined') {
    localStorage.setItem('plm_language', value)
    document.documentElement.lang = value
  }
}
