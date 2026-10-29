<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { fileBlob, saveBlob } from '@/api/modules/partner'
import { partnerError } from '@/utils/partnerError'
import type { BusinessData } from '@/types/partner'
import PartnerPhoto from '../PartnerPhoto.vue'
const props = defineProps<{ references: BusinessData[] }>()
const url = ref(''), mime = ref(''), title = ref(''), error = ref('')
function close() { if (url.value) URL.revokeObjectURL(url.value); url.value = '' }
async function open(file: BusinessData) { error.value = ''; try { const blob = await fileBlob(file.id); close(); title.value = file.name || '本次实际原件'; mime.value = blob.type; if (!blob.type.startsWith('image/') && !blob.type.includes('pdf')) { saveBlob(blob, file.name || 'quality-evidence'); return } url.value = URL.createObjectURL(blob) } catch (e) { error.value = partnerError(e) } }
onBeforeUnmount(close)
</script>
<template><div class="evidence"><div v-for="file in props.references" :key="file.id" class="evidence-file"><PartnerPhoto :id="file.id"/><el-button link type="primary" @click="open(file)">查看 {{ file.name || '本次原件／照片' }} · {{ file.version || '' }}</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-dialog :model-value="!!url" :title="title" width="min(900px,96vw)" append-to-body @close="close"><img v-if="mime.startsWith('image/')" :src="url" alt="本次实际质量依据"/><iframe v-else :src="url" title="本次质量原件"/></el-dialog></div></template>
<style scoped>.evidence{display:flex;flex-wrap:wrap;gap:8px;min-width:0}.evidence-file{display:flex;align-items:center;gap:8px;min-width:0;max-width:100%}.evidence :deep(.el-button){height:auto;white-space:normal;max-width:100%;line-height:1.6;text-align:left}.evidence-file :deep(.partner-photo){width:88px;height:70px}img{display:block;max-width:100%;max-height:75vh;margin:auto}iframe{width:100%;height:70vh;border:0}</style>
