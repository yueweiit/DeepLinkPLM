<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { currentLanguage, languages, t, type Language } from '@/locales'
import { useUserStore } from '@/stores/user'

const user = useUserStore()
const saving = ref(false)
async function change(event: Event) {
  const select = event.target as HTMLSelectElement
  const language = select.value as Language
  select.value = currentLanguage.value
  if (language === currentLanguage.value) return
  saving.value = true
  try { await user.changeLanguage(language) }
  catch { ElMessage.error(t('language.saveFailed')) }
  finally { saving.value = false }
}
</script>

<template>
  <select class="language-switcher" :aria-label="t('language.label')" :value="currentLanguage" :disabled="saving" @change="change">
    <option v-for="language in languages" :key="language.value" :value="language.value">{{ language.label }}</option>
  </select>
</template>

<style scoped>
.language-switcher{max-width:112px;min-height:30px;padding:3px 8px;border:1px solid var(--plm-color-border-light,#dcdfe6);border-radius:4px;background:var(--plm-color-navbar,#fff);color:inherit;font:inherit;cursor:pointer}
.language-switcher:disabled{cursor:wait;opacity:.65}
</style>
