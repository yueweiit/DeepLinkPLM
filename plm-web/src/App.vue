<script setup lang="ts">
import { computed, watchEffect } from 'vue'
import zh from 'element-plus/es/locale/lang/zh-cn'
import es from 'element-plus/es/locale/lang/es'
import { currentLanguage } from '@/locales'
const elementLanguage = computed(() => currentLanguage.value === 'es' ? es : zh)
watchEffect(() => { document.documentElement.lang = currentLanguage.value })
</script>

<template>
  <el-config-provider :locale="elementLanguage"><router-view /></el-config-provider>
</template>
