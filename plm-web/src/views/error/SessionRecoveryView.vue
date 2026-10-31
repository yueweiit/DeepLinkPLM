<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { t } from '@/locales'

const route = useRoute(), router = useRouter(), loading = ref(false)
async function retry() {
  loading.value = true
  try {
    await router.replace(typeof route.query.redirect === 'string' ? route.query.redirect : '/')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="error-shell">
    <section class="page-panel recovery-panel" aria-labelledby="recovery-title">
      <h1 id="recovery-title">{{ t('恢复连接') }}</h1>
      <p role="status">{{ t('暂时无法连接系统，登录资料已保留。请稍后重试。') }}</p>
      <el-button type="primary" :loading="loading" @click="retry">{{ t('重试恢复') }}</el-button>
    </section>
  </div>
</template>

<style scoped>
.error-shell{display:grid;min-height:100vh;min-height:100svh;place-items:center;padding:24px;box-sizing:border-box}
.recovery-panel{width:min(420px,100%);box-sizing:border-box;text-align:center}
.recovery-panel h1{margin:0 0 12px;font-size:24px}
.recovery-panel p{margin:0 0 20px;line-height:1.7;color:var(--plm-color-text-secondary)}
</style>
