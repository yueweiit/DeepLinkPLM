<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getAttachmentDetail, previewAttachment, type AttachmentVO } from '@/api/modules/attachment'
import { useUserStore } from '@/stores/user'
const props = defineProps<{ file: AttachmentVO; compact?: boolean }>()
const user = useUserStore(), url = ref(''), content = ref(''), kind = ref(''), error = ref(''), loading = ref(false), target = ref<AttachmentVO>()
let generation = 0
function clear() { if (url.value) URL.revokeObjectURL(url.value); url.value = ''; content.value = '' }
async function load() {
  const ticket = ++generation, token = user.token; clear(); error.value = ''; loading.value = true; target.value = undefined
  try {
    const file = props.file, ext = file.fileExt.toLowerCase()
    const supported = ['pdf','jpg','jpeg','png','webp','txt','csv'].includes(ext)
    const actual = !supported && file.derivedPdfAttachmentId ? await getAttachmentDetail(file.derivedPdfAttachmentId) : file
    if (ticket !== generation || token !== user.token) return
    target.value = actual; kind.value = actual.fileExt.toLowerCase() === 'pdf' ? 'pdf' : ['jpg','jpeg','png','webp'].includes(actual.fileExt.toLowerCase()) ? 'image' : ['txt','csv'].includes(actual.fileExt.toLowerCase()) ? 'text' : 'unsupported'
    if (kind.value === 'unsupported') return
    const blob = await previewAttachment(actual.attachmentId)
    if (ticket !== generation || token !== user.token) return
    if (kind.value === 'text') { if (blob.size > 2 * 1024 * 1024) { kind.value = 'unsupported'; return } const value = await blob.text(); if (ticket === generation && token === user.token) content.value = value }
    else url.value = URL.createObjectURL(blob)
  } catch (e) { if (ticket === generation && token === user.token) error.value = e instanceof Error ? e.message : '预览加载失败' }
  finally { if (ticket === generation) loading.value = false }
}
watch(() => props.file.attachmentId, load, { immediate: true })
watch(() => user.token, () => { generation++; clear(); target.value = undefined; loading.value = false })
onBeforeUnmount(() => { generation++; clear() })
</script>
<template>
  <section class="document-preview" :class="{'document-preview--compact':compact}" v-loading="loading"><div class="preview-caption" v-if="target">预览 {{ target.originalFileName }} · {{ target.versionNo }}<span v-if="target.attachmentId !== file.attachmentId">（本版本关联的 PDF）</span></div><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-button v-if="error" size="small" @click="load">重试预览</el-button><iframe v-if="url && kind === 'pdf'" :src="url" title="图纸 PDF 预览"/><img v-else-if="url && kind === 'image'" :src="url" :alt="target?.originalFileName"/><pre v-else-if="kind === 'text' && !error">{{ content }}</pre><div v-else-if="kind === 'unsupported' && !error" class="unsupported">当前格式请下载原文件查看；CAD 可关联 PDF 后在此预览。</div></section>
</template>
<style scoped>
.document-preview{display:flex;flex-direction:column;gap:8px;min-height:420px;font-size:12px}.preview-caption{color:#66798e;overflow-wrap:anywhere}.document-preview iframe{border:1px solid #e1e7ee;width:100%;height:60vh;min-height:380px;box-sizing:border-box}.document-preview img{max-width:100%;max-height:65vh;object-fit:contain}.document-preview pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:60vh;overflow:auto;line-height:18px}.unsupported{display:grid;place-content:center;min-height:360px;background:#f7f9fc;color:#697d92;text-align:center;padding:12px}
.document-preview--compact{min-height:0;height:104px;gap:0;overflow:hidden}.document-preview--compact .preview-caption{display:none}.document-preview--compact img{width:100%;height:104px;max-height:104px;object-fit:contain}.document-preview--compact .unsupported{min-height:0;height:104px;padding:4px}.document-preview--compact :deep(.el-alert){padding:4px}.document-preview--compact :deep(.el-alert__title){font-size:11px}
</style>
