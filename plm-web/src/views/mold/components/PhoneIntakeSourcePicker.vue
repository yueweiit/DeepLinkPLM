<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { catalogSuppliers, catalogStyles, catalogStyle } from '@/api/modules/partnerCatalog'
import { useUserStore } from '@/stores/user'
import { partnerError } from '@/utils/partnerError'
import type { CatalogModelEntry, CatalogStyle, CatalogSupplier } from '@/types/partnerCatalog'
import type { PhoneIntakeEntryInput, PhoneIntakeSourceInput } from '@/types/phoneModelIntake'
import PartnerFiles from '@/views/partner/PartnerFiles.vue'
const props = defineProps<{ disabled: boolean }>()
const emit = defineEmits<{ selected: [PhoneIntakeSourceInput] }>()
const user = useUserStore(), supplierCode = ref(''), suppliers = ref<CatalogSupplier[]>([]), supplierQuery = ref(''), supplierPage = ref(1), supplierTotal = ref(0)
const styles = ref<CatalogStyle[]>([]), styleQuery = ref(''), stylePage = ref(1), styleTotal = ref(0), styleKey = ref(''), row = ref<CatalogStyle>(), loading = ref(false), error = ref('')
const all = ref(false), keyword = ref(''), page = ref(1), selected = ref<Record<string, boolean>>({})
const key = (entry: CatalogModelEntry) => String(entry.supplierIndex)
const entries = computed(() => row.value?.supplierModelEntries || [])
const filtered = computed(() => entries.value.filter(entry => (all.value || !entry.phoneModelIds.length || ['unmatched', 'partial'].includes(entry.matchState || '')) && (!keyword.value.trim() || [String(entry.supplierIndex), entry.rawBrandGroup, entry.rawModelText, entry.rawStatus].join(' ').toLowerCase().includes(keyword.value.trim().toLowerCase()))))
const visible = computed(() => filtered.value.slice((page.value - 1) * 20, page.value * 20))
const selectedEntries = computed(() => entries.value.filter(entry => selected.value[key(entry)]))
let supplierGeneration = 0, styleGeneration = 0, detailGeneration = 0
function searchSuppliers(query: string) { void loadSuppliers(query, true) }
function searchStyles(query: string) { void loadStyles(query, true) }
async function loadSuppliers(query = supplierQuery.value, reset = false) {
  if (!user.token || props.disabled) return
  if (reset) supplierPage.value = 1
  supplierQuery.value = query; const serial = ++supplierGeneration, token = user.token
  error.value = ''
  try { const result = await catalogSuppliers({ q: query.trim(), page: supplierPage.value, size: 20 }); if (serial === supplierGeneration && token === user.token) { suppliers.value = result.content; supplierTotal.value = result.totalElements } }
  catch (cause) { if (serial === supplierGeneration && token === user.token) error.value = partnerError(cause) }
}
async function loadStyles(query = styleQuery.value, reset = false) {
  if (!supplierCode.value || props.disabled) return
  if (reset) stylePage.value = 1
  styleQuery.value = query; const serial = ++styleGeneration, supplier = supplierCode.value, token = user.token
  error.value = ''; loading.value = true
  try { const result = await catalogStyles({ supplierCode: supplier, keyword: query.trim(), page: stylePage.value, size: 20 }); if (serial === styleGeneration && token === user.token && supplier === supplierCode.value) { styles.value = result.content; styleTotal.value = result.totalElements } }
  catch (cause) { if (serial === styleGeneration && token === user.token) error.value = partnerError(cause) }
  finally { if (serial === styleGeneration) loading.value = false }
}
function changedSupplier() { styleGeneration++; detailGeneration++; styleKey.value = ''; styles.value = []; row.value = undefined; selected.value = {}; styleQuery.value = ''; stylePage.value = 1; void loadStyles() }
async function changedStyle() {
  const selectedRow = styles.value.find(value => identity(value) === styleKey.value)
  if (!selectedRow || props.disabled) return
  const serial = ++detailGeneration, supplier = supplierCode.value, token = user.token
  row.value = undefined; selected.value = {}; page.value = 1; keyword.value = ''; all.value = false; loading.value = true; error.value = ''
  try { const result = await catalogStyle(supplier, selectedRow); if (serial === detailGeneration && token === user.token && supplier === supplierCode.value) row.value = result }
  catch (cause) { if (serial === detailGeneration && token === user.token) error.value = partnerError(cause) }
  finally { if (serial === detailGeneration) loading.value = false }
}
function identity(value: CatalogStyle) { return `${value.sourceKind}:${value.recordId}:${value.rowId}` }
function next() {
  if (!row.value || !selectedEntries.value.length || props.disabled || loading.value) return
  const value = row.value
  const inputs: PhoneIntakeEntryInput[] = selectedEntries.value.map(entry => ({ supplierIndex: entry.supplierIndex, componentText: entry.rawModelText, classification: 'review', reviewReason: 'name', reason: '' }))
  emit('selected', { supplierCode: value.supplierCode, sourceKind: value.sourceKind, recordId: value.recordId, rowId: value.rowId, rowVersion: value.rowVersion, recordVersion: value.recordVersion, entries: inputs })
}
watch([all, keyword], () => { page.value = 1 })
watch(() => user.token, () => { supplierGeneration++; styleGeneration++; detailGeneration++; suppliers.value = []; styles.value = []; row.value = undefined; selected.value = {} })
onBeforeUnmount(() => { supplierGeneration++; styleGeneration++; detailGeneration++ })
void loadSuppliers()
</script>
<template>
  <section class="intake-source-picker" aria-label="从供应商款式收集待补项">
    <div class="source-selectors"><el-select v-model="supplierCode" filterable remote :remote-method="searchSuppliers" :disabled="disabled" aria-label="待补来源供应商" placeholder="先选择供应商" @change="changedSupplier"><el-option v-for="supplier in suppliers" :key="supplier.supplierCode" :label="supplier.supplierName" :value="supplier.supplierCode"/><template #footer><el-pagination v-if="supplierTotal>20" v-model:current-page="supplierPage" :page-size="20" :total="supplierTotal" :pager-count="5" layout="prev,pager,next" @current-change="loadSuppliers()"/></template></el-select><el-select v-model="styleKey" filterable remote :remote-method="searchStyles" :disabled="disabled||!supplierCode" aria-label="待补来源款式" placeholder="搜索并选择这个供应商的款式" @change="changedStyle"><el-option v-for="style in styles" :key="identity(style)" :label="style.name" :value="identity(style)"/><template #footer><el-pagination v-if="styleTotal>20" v-model:current-page="stylePage" :page-size="20" :total="styleTotal" :pager-count="5" layout="prev,pager,next" @current-change="loadStyles()"/></template></el-select></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <div v-if="row" v-loading="loading"><strong>{{row.supplierName}} · {{row.name}}</strong><template v-if="entries.length"><div class="source-model-tools"><el-input v-model="keyword" size="small" clearable aria-label="来源型号原序号搜索" placeholder="搜索原序号、型号或进度"/><el-checkbox v-model="all">查看全部原条目</el-checkbox><span>已选 {{selectedEntries.length}} 条</span></div><div class="source-model-list"><article v-for="entry in visible" :key="key(entry)"><el-checkbox v-model="selected[key(entry)]" :disabled="disabled" :aria-label="`收集原序号${entry.supplierIndex}`"/><div><strong>{{entry.supplierIndex}} · {{entry.rawBrandGroup}} {{entry.rawModelText}}</strong><p>{{entry.rawStatus||'原表未注明进度'}}{{entry.rawNote?` · ${entry.rawNote}`:''}}</p><small v-if="entry.phones?.length">已有关联：{{entry.phones.map(phone=>phone.modelName).join('、')}}</small></div></article><p v-if="!visible.length">当前筛选没有条目，可切换“查看全部原条目”继续核对。</p></div><el-pagination v-if="filtered.length>20" v-model:current-page="page" :page-size="20" :total="filtered.length" :pager-count="5" layout="total,prev,pager,next"/><el-button type="primary" :disabled="!selectedEntries.length||disabled||loading" @click="next">核对已选型号片段（{{selectedEntries.length}}）</el-button></template><p v-else class="source-note">本款尚无已核对的型号表。请先在供应商目录的本款详情核对来源型号表，不把空资料当成供应商未提供。</p><PartnerFiles v-if="row.modelSheetFileDetails?.length" kind="partner-selections" :id="row.recordId" :files="row.modelSheetFileDetails" :model-value="row.modelSheetRefs||[]" :can-write="false" :can-write-money="false" compact/></div>
  </section>
</template>
<style scoped>
.intake-source-picker{font-size:13px;min-width:0}.source-selectors,.source-model-tools{display:flex;align-items:center;gap:7px;flex-wrap:wrap;margin-bottom:7px}.source-selectors>.el-select{min-width:180px;flex:1}.source-model-tools{margin-top:7px}.source-model-tools>.el-input{min-width:170px;flex:1}.source-model-list{max-height:40dvh;overflow:auto;border:1px solid #e3eaf2;border-radius:4px;margin:7px 0}.source-model-list article{display:flex;gap:8px;align-items:flex-start;padding:6px 8px;border-bottom:1px solid #e4ebf4}.source-model-list article>div{flex:1;min-width:0;overflow-wrap:anywhere}.source-model-list .el-checkbox{height:25px}.source-model-list p{margin:3px 0;white-space:pre-wrap;line-height:1.6}.source-model-list small,.source-note{font-size:12px;color:#66788e}.source-note{line-height:1.6}.source-model-list strong{font-size:13px}.el-pagination{justify-content:flex-end}
</style>
