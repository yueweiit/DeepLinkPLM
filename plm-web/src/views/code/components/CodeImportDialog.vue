<script setup lang="ts">
import { t as uiText } from '@/locales'

import { Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { ref } from 'vue'
import { commitCodeImport, previewCodeImport, type CodeImportPreview } from '@/api/modules/code'

defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: boolean): void; (event: 'committed'): void }>()
const loading = ref(false)
const preview = ref<CodeImportPreview | null>(null)

async function selectFile(file: { raw?: File }) {
  if (!file.raw) return false
  loading.value = true
  try { preview.value = await previewCodeImport(file.raw) }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : uiText('颜色编码预校验失败')) }
  finally { loading.value = false }
  return false
}

async function commit() {
  if (!preview.value || preview.value.errorCount) return
  loading.value = true
  try {
    const result = await commitCodeImport(preview.value.importToken)
    ElMessage.success(uiText('已导入 {value0} 条颜色编码',{value0:(result.committedCount)}))
    emit('committed'); emit('update:modelValue', false)
  } finally { loading.value = false }
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title='uiText("批量导入颜色编码")' width="760px" destroy-on-close @close="emit('update:modelValue', false)">
    <el-alert :title='uiText("支持 Códigos de color 或 颜色编码工作表，第 6 行表头、第 7 行起为颜色数据。西语模板更新原名称，中文模板更新中文名称。")' type="info" show-icon :closable="false" />
    <el-upload class="import-drop" drag :auto-upload="false" :show-file-list="false" accept=".xlsx" :on-change="selectFile">
      <el-icon class="import-drop__icon"><Upload /></el-icon><div>{{ uiText("拖入 XLSX，或点击选择文件") }}</div>
    </el-upload>
    <div v-if="preview" class="preview-stats">
      <span class="is-create">{{ uiText("新增 ") }}{{ preview.createCount }}</span><span>{{ uiText("更新 ") }}{{ preview.updateCount }}</span><span>{{ uiText("无变化 ") }}{{ preview.unchangedCount }}</span><span class="is-error">{{ uiText("错误 ") }}{{ preview.errorCount }}</span>
    </div>
    <el-table v-if="preview?.rows.length" :data="preview.rows" max-height="300" border>
      <el-table-column prop="rowNo" :label='uiText("行")' width="64" /><el-table-column prop="codeValue" :label='uiText("编码")' width="100" />
      <el-table-column prop="codeName" :label='uiText("原名称")' /><el-table-column prop="codeNameZh" :label='uiText("中文名称")' /><el-table-column prop="status" :label='uiText("状态")' width="100" /><el-table-column prop="action" :label='uiText("处理")' width="100" />
    </el-table>
    <el-alert v-for="error in preview?.errors || []" :key="`${error.rowNo}-${error.field}`" type="error" :title="uiText('第 {value0} 行 {value1}：{value2}',{value0:(error.rowNo),value1:(error.field),value2:(error.reason)})" :closable="false" />
    <template #footer><el-button @click="emit('update:modelValue', false)">{{ uiText("取消") }}</el-button><el-button type="primary" :loading="loading" :disabled="!preview || preview.errorCount > 0 || preview.createCount + preview.updateCount === 0" @click="commit">{{ uiText("确认导入") }}</el-button></template>
  </el-dialog>
</template>

<style scoped>.import-drop { display: block; margin: 16px 0; }.import-drop__icon { margin-bottom: 8px; font-size: 30px; color: var(--el-color-primary); }.preview-stats { display: flex; gap: 18px; margin-bottom: 12px; font-weight: 600; }.is-create { color: var(--el-color-success); }.is-error { color: var(--el-color-danger); }</style>
