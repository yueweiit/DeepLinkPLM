<script setup lang="ts">
import { ref } from 'vue'
import { operationAction, uploadOperationFile } from '@/api/modules/partnerOperations'
import { newRequestId } from '@/api/modules/partner'
import type { PartnerRow } from '@/types/partner'
import { partnerError } from '@/utils/partnerError'
import InspectionResult from './InspectionResult.vue'
const props = defineProps<{ record: PartnerRow }>()
const emit = defineEmits<{ signed: [record: PartnerRow]; close: [] }>()
const canvas = ref<HTMLCanvasElement>(), name = ref(''), position = ref(''), reviewed = ref(false), busy = ref(false), error = ref('')
let drawing = false, distance = 0, last: { x: number; y: number } | undefined, fileId: number | undefined
const requestId = newRequestId()
function point(e: PointerEvent) { const rect = canvas.value!.getBoundingClientRect(); return { x: (e.clientX - rect.left) * 640 / rect.width, y: (e.clientY - rect.top) * 200 / rect.height } }
function start(e: PointerEvent) { if (busy.value) return; drawing = true; last = point(e); canvas.value?.setPointerCapture?.(e.pointerId) }
function move(e: PointerEvent) { if (!drawing || !last) return; const next = point(e), ctx = canvas.value!.getContext('2d'); if (!ctx) return; ctx.strokeStyle = '#182b49'; ctx.lineWidth = 3; ctx.lineCap = 'round'; ctx.beginPath(); ctx.moveTo(last.x, last.y); ctx.lineTo(next.x, next.y); ctx.stroke(); distance += Math.hypot(next.x - last.x, next.y - last.y); last = next; fileId = undefined }
function clear() { canvas.value?.getContext('2d')?.clearRect(0, 0, 640, 200); distance = 0; fileId = undefined }
async function save() { if (!name.value.trim() || !position.value.trim() || !reviewed.value || distance < 40) { error.value = '请工厂代表阅读完整结果和问题，填写姓名、岗位并本人手写签字。'; return } busy.value = true; error.value = ''
  try { if (!fileId) { const blob = await new Promise<Blob>((resolve, reject) => canvas.value!.toBlob(b => b ? resolve(b) : reject(new Error('签名图片生成失败')), 'image/png')); const uploaded = await uploadOperationFile('inspection', props.record.id, new File([blob], 'factory-signature.png', { type: 'image/png' }), 'factory_signature'); fileId = uploaded.attachmentId }
    const record = await operationAction('inspection', props.record.id, 'sign', { requestId, version: props.record.version, basisHash: props.record.basisHash, reviewed: true, representativeName: name.value.trim(), representativePosition: position.value.trim(), signatureFileId: fileId }); emit('signed', record)
  } catch (e) { error.value = `尚未确认签字成功：${partnerError(e)}。原结果保留，可核对后重试。` } finally { busy.value = false }
}
</script>
<template><el-dialog :model-value="true" title="工厂代表：阅读本次检验并现场签字" fullscreen append-to-body :close-on-click-modal="false" :close-on-press-escape="false" :show-close="!busy" class="factory-signature-dialog" @close="emit('close')"><div class="factory-only"><p class="hint">由工厂指定代表本人操作，无需注册账号。此画面只展示本次质量结果，不展示公司后台、报价或费用。</p><InspectionResult :record="record"/><el-form label-position="top"><el-form-item label="工厂代表真实姓名"><el-input v-model="name" :disabled="busy"/></el-form-item><el-form-item label="岗位"><el-input v-model="position" :disabled="busy"/></el-form-item><el-checkbox v-model="reviewed" :disabled="busy">我已读完本次检查结果、完整问题清单和适用范围</el-checkbox><p>请本人在下面手写签字：</p><canvas ref="canvas" width="640" height="200" aria-label="工厂代表本人手写签字区域" @pointerdown.prevent="start" @pointermove.prevent="move" @pointerup="drawing = false" @pointercancel="drawing = false"/><el-button :disabled="busy" @click="clear">清空重签</el-button></el-form><el-alert v-if="error" :title="error" type="error" :closable="false"/><div class="signature-actions"><el-button :disabled="busy" @click="emit('close')">暂不签字，交回工程</el-button><el-button type="primary" :loading="busy" @click="save">本人确认签署本次结果</el-button></div></div></el-dialog></template>
<style scoped>.factory-only{max-width:850px;margin:auto;min-width:0;overflow-wrap:anywhere;padding:0 4px 25px}canvas{display:block;width:100%;max-width:640px;aspect-ratio:3.2;border:1px solid #aab8cb;border-radius:8px;background:#f8fafc;touch-action:none;margin:10px 0}.hint{font-size:13px;color:#6c7f96;line-height:1.6}.signature-actions{display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap;margin-top:16px}.factory-only :deep(.el-checkbox){white-space:normal;height:auto;align-items:flex-start}.factory-only :deep(.el-checkbox__label){white-space:normal;line-height:1.6}.signature-actions :deep(.el-button){height:auto;white-space:normal;line-height:1.6}</style>
