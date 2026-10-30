<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getSalesCatalog, saveSalesProduct, type SalesProduct } from '@/api/modules/productionMetadata'
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace'
import { useUserStore } from '@/stores/user'

type CatalogRow = SalesProduct & { pendingStatus: SalesProduct['salesStatus']; aliasText: string }
const visible = defineModel<boolean>({ default: false })
const workspace = useCollaborationWorkspace(), user = useUserStore()
const rows = ref<CatalogRow[]>([]), loading = ref(false), saving = ref<number>()
const loadError = ref(''), saveError = ref(''), notice = ref('')
const error = computed(() => saveError.value || loadError.value)
const reason = ref('维护产品在售范围及历史审批款式映射')
let epoch = 0
const aliases = (text: string) => [...new Set(text.split('\n').map(s => s.trim()).filter(Boolean))]
const dirty = (row: CatalogRow) => row.pendingStatus !== row.salesStatus || JSON.stringify(aliases(row.aliasText)) !== JSON.stringify(row.applicationAliases)
const editable = (row: SalesProduct): CatalogRow => ({ ...row, pendingStatus: row.salesStatus, aliasText: row.applicationAliases.join('\n') })

async function load() {
  if (saving.value !== undefined) return
  const ticket = ++epoch, token = user.token
  loading.value = true
  loadError.value = ''
  try {
    const result = await getSalesCatalog()
    if (ticket !== epoch || token !== user.token) return
    // Keep the original values of edited rows so the existing save API still detects conflicts.
    const drafts = new Map(rows.value.filter(dirty).map(row => [row.id, row]))
    rows.value = result.map(row => {
      const draft = drafts.get(row.id)
      if (draft) drafts.delete(row.id)
      return draft || editable(row)
    }).concat([...drafts.values()])
    const pending = rows.value.filter(dirty).length
    notice.value = pending ? `目录已刷新，已保留 ${pending} 项未保存修改；保存时仍校验原值冲突。` : ''
  } catch (e) {
    if (ticket === epoch && token === user.token) loadError.value = e instanceof Error ? e.message : '读取失败'
  } finally {
    if (ticket === epoch && token === user.token) loading.value = false
  }
}

async function save(row: CatalogRow) {
  if (saving.value !== undefined) return
  if (!reason.value.trim()) return ElMessage.warning(uiText('请填写原因'))
  const token = user.token
  epoch++
  loading.value = false
  saving.value = row.id
  saveError.value = ''
  try {
    const result = await saveSalesProduct(row, { salesStatus: row.pendingStatus, applicationAliases: aliases(row.aliasText) }, reason.value)
    if (token !== user.token) return
    Object.assign(row, editable(result))
    workspace.changed()
    ElMessage.success(uiText('已保存；开模覆盖与申请范围已同步'))
  } catch (e) {
    if (token === user.token) saveError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    if (token === user.token) saving.value = undefined
  }
}

// Closing never discards drafts; reopening and shared business changes refresh the same catalog.
watch([visible, () => workspace.revision, saving], ([open, , busy]) => {
  if (open && busy === undefined) void load()
}, { immediate: true })
watch(() => user.token, () => {
  epoch++
  rows.value = []
  visible.value = false
  loading.value = false
  saving.value = undefined
  loadError.value = saveError.value = notice.value = ''
}, { flush: 'sync' })
</script>

<template>
  <el-drawer v-model="visible" :title='uiText("产品在售范围与审批款式别名")' size="min(850px,96vw)">
    <div class="sales-catalog" v-loading="loading">
      <div class="catalog-toolbar"><p>{{ uiText("在此维护一次，开模覆盖、申请产品选择、编码预留与提交统一读取。在售状态与生产状态、归档状态分别保存；停售产品保留历史资料。") }}</p><el-button size="small" :disabled="loading || saving !== undefined" @click="load">{{ uiText("刷新目录") }}</el-button></div>
      <el-input v-model="reason" :placeholder='uiText("修改原因")'/>
      <el-alert v-if="error" :title="error" type="error" :closable="false"/>
      <p v-if="notice" class="catalog-notice" role="status">{{ notice }}</p>
      <article v-for="p in rows" :key="p.id">
        <strong>{{ p.productName }}</strong>
        <el-select v-model="p.pendingStatus" size="small" :disabled="saving === p.id"><el-option value="on_sale" :label='uiText("在售 · 纳入覆盖")'/><el-option value="off_sale" :label='uiText("停售 · 保留历史")'/><el-option value="unconfirmed" :label='uiText("未确认")'/></el-select>
        <el-input v-model="p.aliasText" type="textarea" :rows="2" :disabled="saving === p.id" :placeholder='uiText("审批原款式别名，每行一个（精确匹配，保留工艺差异）")'/>
        <el-button size="small" :loading="saving === p.id" :disabled="saving !== undefined" @click="save(p)">{{ uiText("保存") }}</el-button>
      </article>
    </div>
  </el-drawer>
</template>

<style scoped>
.sales-catalog{font-size:12px}.sales-catalog p{color:#64768b;line-height:1.6}.catalog-toolbar{display:flex;align-items:center;gap:12px}.catalog-toolbar p{flex:1}.catalog-notice{margin:8px 0}.sales-catalog article{display:grid;grid-template-columns:110px 160px minmax(160px,1fr) 54px;gap:8px;align-items:center;border-bottom:1px solid #e4eaf2;padding:8px 0}.sales-catalog .el-input{margin-bottom:8px}@media(max-width:700px){.sales-catalog article{grid-template-columns:1fr 1fr}.sales-catalog article .el-textarea{grid-column:1/-1;grid-row:2}}
</style>
