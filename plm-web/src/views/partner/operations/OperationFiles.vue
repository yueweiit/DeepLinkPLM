<script setup lang="ts">
import { ref, onBeforeUnmount } from 'vue'
import { uploadOperationFile, type OperationKind } from '@/api/modules/partnerOperations'
import { fileBlob, saveBlob } from '@/api/modules/partner'
import { partnerError } from '@/utils/partnerError'
import PartnerPhoto from '../PartnerPhoto.vue'
import type { BusinessData } from '@/types/partner'
const props = defineProps<{ kind: OperationKind; id: number; files: BusinessData[]; modelValue: BusinessData[]; canWrite: boolean; canCost?:boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: BusinessData[]]; changed: [] }>()
const busy = ref(false), error = ref(''), preview = ref(''), previewType = ref(''), title = ref(''), costFile = ref(false)
function adopt(file: BusinessData, checked: boolean) { emit('update:modelValue', checked ? [...props.modelValue.filter(f => f.id !== file.id), { id: file.id }] : props.modelValue.filter(f => f.id !== file.id)) }
async function upload(file: File) { busy.value = true; error.value = ''; try { const saved = await uploadOperationFile(props.kind, props.id, file, costFile.value ? 'cooperation_cost' : file.type.startsWith('image/') ? 'photo' : 'material'); emit('update:modelValue', [...props.modelValue, { id: saved.attachmentId }]); emit('changed') } catch (e) { error.value = `文件未保存：${partnerError(e)}。本次不应确认缺少该文件的结果。` } finally { busy.value = false } return false }
function closePreview() { if (preview.value) URL.revokeObjectURL(preview.value); preview.value = '' }
async function open(file: BusinessData) { try { closePreview(); const blob = await fileBlob(file.id); title.value = file.name; previewType.value = blob.type; preview.value = URL.createObjectURL(blob) } catch (e) { error.value = partnerError(e) } }
async function download(file: BusinessData) { try { saveBlob(await fileBlob(file.id, false), file.name) } catch (e) { error.value = partnerError(e) } }
onBeforeUnmount(closePreview)
defineExpose({ busy, error })
</script>
<template><section class="operation-files"><header><h4>图片、附件与本次采用依据</h4><template v-if="canWrite"><el-upload :show-file-list="false" :before-upload="upload" :disabled="busy" accept=".png,.jpg,.jpeg,.webp"><el-button size="small" :loading="busy">上传图片</el-button></el-upload><el-upload :show-file-list="false" :before-upload="upload" :disabled="busy"><el-button size="small" :loading="busy">上传附件</el-button></el-upload></template></header><p class="hint">有报告附原件；没有报告如实填写检查结果，不重复录整份报告。</p><p v-if="!files.some(file=>String(file.type).startsWith('image/'))" class="hint no-image">暂无图片；可直接添加首张实际图片，附件不代替检验结论。</p><div v-for="file in files" :key="file.id" class="file"><PartnerPhoto v-if="String(file.type).startsWith('image/')" :id="file.id"/><el-checkbox v-if="canWrite" :model-value="modelValue.some(f => f.id === file.id)" @change="adopt(file, Boolean($event))">本次采用</el-checkbox><span>{{ file.name }} · {{ file.version }}</span><el-button link @click="open(file)">查看</el-button><el-button link @click="download(file)">下载</el-button></div><el-checkbox v-if="canWrite && canCost && kind === 'fulfilment'" v-model="costFile">费用／服务费凭据（仅获授权工程／财务可见）</el-checkbox><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-dialog :model-value="!!preview" :title="title" width="min(850px,95vw)" append-to-body @close="closePreview"><img v-if="previewType.startsWith('image/')" :src="preview"/><iframe v-else-if="previewType.includes('pdf')" :src="preview" title="原件预览"/><el-empty v-else description="此格式请下载查看"/></el-dialog></section></template>
<style scoped>h4{font-size:14px;margin:0 0 6px}.operation-files{min-width:0}header{display:flex;align-items:center;gap:8px;flex-wrap:wrap}header h4{flex:1}header .el-upload{flex:none}.file{display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:6px 0}.file>span{flex:1;overflow-wrap:anywhere;min-width:80px}.hint{font-size:13px;color:#708297}img{display:block;max-width:100%;max-height:70vh;margin:auto}iframe{width:100%;height:65vh;border:0}</style>
