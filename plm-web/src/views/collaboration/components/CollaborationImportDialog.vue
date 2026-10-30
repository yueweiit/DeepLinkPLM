<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as api from '@/api/modules/collaboration'
import * as schedule from '@/api/modules/schedule'
import type { CellValue, CollaborationOptions, CollaborationRow, ImportDecision, ImportPreview, ImportRow, PlanField, ImportKind } from '@/types/collaboration'
import { collaborationFieldEditable, parseCell } from '@/utils/collaboration'
import type { PhoneBrand } from '@/types/molds'
import CollaborationCell from './CollaborationCell.vue'
import CollaborationObjectSelect from './CollaborationObjectSelect.vue'
import type { PhoneObjectOption } from '@/components/PhoneObjectSelect/types'
const props = defineProps<{ modelValue: boolean; kind: ImportKind; options: CollaborationOptions; brands?:PhoneBrand[] }>()
const isMold = computed(() => ['delivery','schedule'].includes(props.kind))
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; imported: [] }>()
const visible = computed({ get: () => props.modelValue, set: v => emit('update:modelValue', v) })
const preview = ref<ImportPreview>(); const decisions = ref<ImportDecision[]>([]); const selectedIndex = ref(0)
const selected = computed(() => decisions.value[selectedIndex.value]); const source = computed(() => preview.value?.rows[selectedIndex.value])
const busy = ref(false); const failure = ref(''); const selectedFile = ref<File>(); const fileInput = ref<HTMLInputElement>()
const styleMap = reactive<Record<string, number | null>>({}); const bulkReason = ref('')
const styleLabels = computed(() => [...new Set(preview.value?.rows.map(r => r.styleName).filter(Boolean) || [])])
const setOptions = ref<CollaborationRow[]>([])
const included = computed(() => decisions.value.filter(r => !r.skip).length)
const sourceFields = computed(() => (isMold.value ? props.options.moldFields : props.options.itemFields).filter(f => f.group!=='finance' && (isMold.value ? !['pcMoldCodeId','tpuMoldCodeId','pcInventoryId','tpuInventoryId'].includes(f.key) : (f.group === props.kind || props.kind==='market'&&f.key==='brandId') && !['productLineId','brand'].includes(f.key))))
function downloadTemplate() { return props.kind === 'schedule' ? schedule.downloadScheduleTemplate() : api.downloadCollaborationTemplate(props.kind) }
watch(() => props.modelValue, v => { if (v) { preview.value = undefined; decisions.value = []; selectedFile.value = undefined; selectedIndex.value = 0; failure.value = ''; bulkReason.value = ''; setOptions.value = []; Object.keys(styleMap).forEach(k => delete styleMap[k]) } })
watch(selectedIndex, () => { void loadSets(selected.value) })
function fileChosen(event: Event) { selectedFile.value = (event.target as HTMLInputElement).files?.[0] }
async function upload() {
  if (!selectedFile.value) return
  busy.value = true; failure.value = ''
  try {
    preview.value = props.kind === 'schedule' ? await schedule.previewScheduleImport(selectedFile.value) : await api.previewCollaborationImport(props.kind, selectedFile.value)
    decisions.value = preview.value.rows.map(r => ({ ...r, values: { ...r.values }, before: { ...r.before }, skip: r.alreadyImported || !!r.issues.length, reviewReason: '' }))
    selectedIndex.value = 0
    await loadSets(selected.value)
  } catch (e) { failure.value = e instanceof Error ? e.message : '预览失败，请核对文件格式' } finally { busy.value = false }
}
const targetReferences=computed<PhoneObjectOption[]>({get:()=>selected.value?.itemId?[{type:'item',id:selected.value.itemId,name:selected.value.compatibleModels,code:`#${selected.value.itemId}`,productLineName:selected.value.styleName}]:[],set:rows=>{if(selected.value)void chooseTarget(selected.value,rows[0]?.id||null)}})
function baseline(row: ImportDecision, target: CollaborationRow) { row.before = Object.fromEntries([...new Set([...Object.keys(row.values),...(isMold.value?['openedDate','plannedMoldDeliveryDate','arrivalConfirmedBasisDate']:[])])].map(key => [key, (target[key] as CellValue) ?? null])) }
async function loadSets(row?: ImportDecision) {
  if (!row?.itemId || !isMold.value) { setOptions.value = []; return }
  const sets = await api.getCollaborationPage({ view: 'delivery', page: 1, size: 100, itemId: row.itemId })
  if (selected.value === row) setOptions.value = sets.content
}
async function chooseTarget(row: ImportDecision, id: number | null) {
  row.itemId = id; row.moldSetId = null; row.before = {}; row.newMoldSet = false
  if (!id) { row.newMoldSet = props.kind === 'delivery'; return }
  const target = await api.getCollaborationItem(id); if (row.itemId !== id) return
  row.productLineId = target.productLineId; row.styleName = target.styleName; row.compatibleModels = target.compatibleModels
  if (!isMold.value) {
    baseline(row, target)
    // Choosing a canonical target does not rename its model group with a source alias.
    if (props.kind === 'market') { row.values.styleName = target.styleName; row.values.compatibleModels = target.compatibleModels }
  } else {
    const result = await api.getCollaborationPage({ view: 'delivery', page: 1, size: 100, itemId: id })
    if (selected.value === row) setOptions.value = result.content
    if (result.content.length === 1) { row.moldSetId = result.content[0].moldSetId!; baseline(row, result.content[0]) }
    else if (!result.content.length) row.newMoldSet = props.kind === 'delivery'
  }
}
function chooseSet(row: ImportDecision, id: number | null) { row.moldSetId = id; const set = setOptions.value.find(s => s.moldSetId === id); if (set) baseline(row,set) }
async function applyStyleMapping(style: string, lineId: number | null) {
  const line = props.options.productLines.find(l => l.productLineId === lineId); if (!line) return
  busy.value = true
  try {
    for (let n = 0; n < decisions.value.length; n++) {
      const row = decisions.value[n]; if (preview.value?.rows[n].styleName !== style || preview.value?.rows[n].alreadyImported) continue
      row.productLineId = line.productLineId; row.styleName = line.productName
      if (props.kind === 'market') row.values.styleName = line.productName
      const matches = (await api.getCollaborationPage({ view: 'market', page: 1, size: 100, productLineId: line.productLineId, keyword: row.compatibleModels.slice(0,128) })).content.filter(i => i.compatibleModels.trim().toLowerCase() === row.compatibleModels.trim().toLowerCase())
      if (matches.length === 1) await chooseTarget(row,matches[0].itemId)
      else { row.itemId = null; row.moldSetId = null; row.before = {}; row.newMoldSet = props.kind === 'delivery' }
    }
    ElMessage.success(uiText('款式映射已暂存，请继续核对品牌、型号和日期异常'))
  } finally { busy.value = false }
}
function editValue(row: ImportDecision, field: PlanField, text: string, quiet = false) {
  try {
    row.values[field.key] = parseCell(field,text)
    if (!row.itemId && ['styleName','compatibleModels'].includes(field.key)) row[field.key as 'styleName' | 'compatibleModels'] = String(row.values[field.key] ?? '')
  } catch (e) { if (!quiet) ElMessage.warning(e instanceof Error ? e.message : uiText('格式不正确')) }
}
function includeReviewed() {
  let count = 0
  decisions.value.forEach((row, n) => {
    if (preview.value?.rows[n].alreadyImported) return
    const issues = preview.value?.rows[n].issues || []
    const mappingOnly = issues.every(t => t.startsWith('未匹配现有档案') || t.startsWith('存在多个匹配档案'))
    if (mappingOnly && (row.itemId || row.productLineId) && bulkReason.value.trim()) { row.reviewReason = bulkReason.value.trim(); row.skip = false; count++ }
  })
  ElMessage.success(uiText('已纳入 {value0} 行；品牌、日期及重复关系异常仍须逐行核对',{value0:(count)}))
}
async function commit() {
  if (!preview.value || !included.value) return
  for (let n = 0; n < decisions.value.length; n++) {
    const row = decisions.value[n]; if (row.skip) continue
    if (preview.value.rows[n].issues.length && !row.reviewReason.trim()) { selectedIndex.value = n; ElMessage.warning(uiText('第 {value0} 行须填写人工核对说明',{value0:(row.rowNo)})); return }
    if (!row.itemId && (!row.styleName.trim() || !row.compatibleModels.trim())) { selectedIndex.value = n; ElMessage.warning(uiText('第 {value0} 行须填写款式和兼容型号',{value0:(row.rowNo)})); return }
    if (props.kind === 'delivery' && !row.newMoldSet && !row.moldSetId) { selectedIndex.value = n; ElMessage.warning(uiText('第 {value0} 行须选择模具套或确认新增一套',{value0:(row.rowNo)})); return }
    if (props.kind === 'schedule' && (!row.itemId || !row.moldSetId || row.newMoldSet)) { selectedIndex.value = n; ElMessage.warning(uiText('第 {value0} 行须选择现有档案与实物模具套；排程不自动建档',{value0:(row.rowNo)})); return }
    if (props.kind === 'schedule' && /[/＋+]/.test(preview.value.rows[n].compatibleModels) && !row.confirmCompatibility) { selectedIndex.value = n; ElMessage.warning(uiText('第 {value0} 行须明确确认合写型号兼容关系',{value0:(row.rowNo)})); return }
    if (props.kind === 'market' && !row.itemId) Object.assign(row.values,{ styleName: row.styleName, compatibleModels: row.compatibleModels })
  }
  busy.value = true; failure.value = ''
  try { const result = props.kind === 'schedule' ? await schedule.commitScheduleImport(preview.value.importToken, decisions.value) : await api.commitCollaborationImport(preview.value.importToken, decisions.value); ElMessage.success(result.duplicate ? uiText('这些来源行已经导入，没有重复创建') : uiText('已导入 {value0} 行，跳过 {value1} 行',{value0:(result.saved),value1:(result.skipped)})); emit('imported'); visible.value = false }
  catch (e) { failure.value = e instanceof Error ? e.message : '提交失败，请检查核对结果；本次导入未完成' }
  finally { busy.value = false }
}
</script>

<template>
  <el-dialog v-model="visible" :title="uiText('Excel 导入预览 · {value0}',{value0:(kind === 'schedule' ? '开模时间管控' : kind === 'delivery' ? '模具交付' : kind === 'production' ? '累计产量' : '市场资料')})" width="min(1400px, 94vw)" top="4vh" :close-on-click-modal="false" destroy-on-close>
    <p class="import-note">{{ uiText("先预览、修正映射，再确认提交。不会从备注猜测产量、根据 $ 猜币种，或自动合并歧义型号。相同文件已导入的来源行不会重复创建。") }}</p>
    <div class="upload-row"><input ref="fileInput" type="file" accept=".xlsx" :aria-label='uiText("选择协作计划 XLSX 文件")' @change="fileChosen" /><el-button type="primary" :disabled="!selectedFile" :loading="busy" @click="upload">{{ uiText("读取预览") }}</el-button><el-button @click="downloadTemplate">{{ uiText("下载标准模板") }}</el-button></div>
    <el-alert v-if="failure" :title="failure" type="error" :closable="false" />
    <template v-if="preview">
      <div class="preview-summary">{{ uiText("共 ") }}{{ preview.total }}{{ uiText(" 行，") }}{{ preview.needsReview }}{{ uiText(" 行需要核对；已选择导入 ") }}{{ included }}{{ uiText(" 行。原始文件和历史项目尚未改动。") }}</div>
      <details class="mapping-box" open><summary>{{ uiText("款式映射（人工选择现有产品线，不自动猜别名）") }}</summary><div class="mapping-grid"><label v-for="style in styleLabels" :key="style"><span>{{ style }}</span><el-select v-model="styleMap[style]" clearable :placeholder='uiText("保持原款式 / 选择产品线")' @change="applyStyleMapping(style,$event)"><el-option v-for="line in options.productLines" :key="line.productLineId" :label="line.productName" :value="line.productLineId" /></el-select></label></div><div class="upload-row"><el-input v-model="bulkReason" :placeholder='uiText("对仅有款式映射问题的行填写核对说明")' /><el-button :disabled="!bulkReason.trim() || busy" @click="includeReviewed">{{ uiText("纳入已核对的映射行") }}</el-button></div></details>
      <div class="import-layout">
        <div class="preview-list"><el-table :data="preview.rows" height="460" highlight-current-row @row-click="(row: ImportRow) => selectedIndex = preview!.rows.indexOf(row)"><el-table-column :label='uiText("导入")' width="60"><template #default="{row,$index}"><el-checkbox :model-value="!decisions[$index].skip" :disabled="row.alreadyImported" @change="decisions[$index].skip = !$event" /></template></el-table-column><el-table-column prop="rowNo" :label='uiText("原表行")' width="70" /><el-table-column :label='uiText("款式 / 手机型号")' min-width="260"><template #default="{row,$index}"><strong>{{ row.styleName }}</strong><p>{{ row.compatibleModels }}</p><small>{{ row.brand }} · {{ decisions[$index].itemId ? uiText('档案 ') + decisions[$index].itemId : uiText('待建立 / 选择档案') }}</small></template></el-table-column><el-table-column :label='uiText("核对状态")' width="145"><template #default="{row,$index}"><el-tag :type="row.alreadyImported ? 'info' : row.issues.length && !decisions[$index].reviewReason ? 'warning' : 'success'" size="small">{{ row.alreadyImported ? uiText('已导入，自动跳过') : row.issues.length && !decisions[$index].reviewReason ? uiText('待人工核对') : uiText('可确认') }}</el-tag></template></el-table-column></el-table></div>
        <article v-if="selected && source" class="row-editor"><h4>{{ source.sheetName }}{{ uiText(" · 第 ") }}{{ selected.rowNo }}{{ uiText(" 行") }}</h4><div class="issues"><p v-for="issue in source.issues" :key="issue">{{ issue }}</p></div><label>{{ uiText("目标协作档案") }}<CollaborationObjectSelect v-model="targetReferences" type="item" :multiple="false" :product-line-id="selected.productLineId" :aria-label='uiText("导入关联已有档案")'/></label>
          <div v-if="!selected.itemId" class="target-fields"><label>{{ uiText("产品线") }}<el-select v-model="selected.productLineId" clearable><el-option v-for="l in options.productLines" :key="l.productLineId" :label="l.productName" :value="l.productLineId" /></el-select></label><label>{{ uiText("款式") }}<el-input v-model="selected.styleName" /></label><label>{{ uiText("原始品牌（只读）") }}<span>{{ source.brand || uiText('未填写') }}</span></label><label>{{ uiText("兼容型号") }}<el-input v-model="selected.compatibleModels" /></label></div>
          <template v-if="isMold"><el-checkbox v-if="kind === 'delivery'" v-model="selected.newMoldSet">{{ uiText("新增一套 PC＋TPU 模具（复制 / 重新开模）") }}</el-checkbox><label>{{ selected.newMoldSet ? uiText('复制来源套（可留空，新套日期等仅使用本次明确填入值）') : uiText('对应模具套') }}<el-select v-model="selected.moldSetId" clearable @change="chooseSet(selected!,$event)"><el-option v-for="s in setOptions" :key="s.moldSetId" :label="`${s.assetNumber || '#'+s.moldSetId} / ${s.setName || ''}`" :value="s.moldSetId!" /></el-select></label><el-checkbox v-if="kind === 'schedule' && /[/＋+]/.test(source.compatibleModels)" v-model="selected.confirmCompatibility">{{ uiText("我已核对合写手机型号，确认所选档案兼容关系") }}</el-checkbox></template>
          <details open><summary>{{ uiText("待写入字段（可修正）") }}</summary><label v-for="f in sourceFields.filter(f => Object.prototype.hasOwnProperty.call(selected!.values,f.key))" :key="f.key"><span>{{ uiText(String((f.label) ?? '')) }}</span><CollaborationCell :field="f" :users="options.users" :brands="brands" :value="selected.values[f.key]" :editable="collaborationFieldEditable(f,options.writableGroups)" @preview="editValue(selected!,f,$event,true)" @edit="editValue(selected!,f,$event)" /></label></details>
          <label>{{ uiText("人工核对说明") }}<el-input v-model="selected.reviewReason" type="textarea" :rows="2" :placeholder='uiText("说明别名映射、品牌或日期修正；数量下调也在此说明")' /></label><el-checkbox :model-value="!selected.skip" :disabled="source.alreadyImported" @change="selected!.skip = !$event">{{ uiText("确认本行纳入导入") }}</el-checkbox><details><summary>{{ uiText("原始行数据（只读）") }}</summary><pre>{{ source.raw }}</pre></details>
        </article>
      </div>
    </template>
    <template #footer><span v-if="preview" class="footer-note">{{ uiText("提交只影响选中的行；有冲突时本次提交回滚，保留预览供修正。") }}</span><el-button @click="visible=false">{{ uiText("关闭预览") }}</el-button><el-button type="primary" :loading="busy" :disabled="!included" @click="commit">{{ uiText("确认导入 ") }}{{ included || '' }}{{ uiText(" 行") }}</el-button></template>
  </el-dialog>
</template>
<style scoped>
.import-note,.footer-note { font-size:12px; color:#77859a; }.upload-row { display:flex; align-items:center; gap:12px; margin:12px 0; }.upload-row input { flex:1; min-width:0; }.preview-summary { margin:15px 0; padding:10px 12px; background:#edf4ff; color:#42648f; border-radius:5px; font-size:13px; }.mapping-box { border:1px solid #e1e8f1; padding:12px; border-radius:6px; margin-bottom:14px; }summary { cursor:pointer; color:#466688; font-size:13px; margin-bottom:9px; }.mapping-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; }.mapping-grid label span { font-size:12px; display:block; margin-bottom:5px; }.import-layout { display:grid; grid-template-columns:minmax(0,1fr) 410px; gap:18px; }.preview-list p { margin:3px 0; font-size:12px; }.preview-list small { color:#8a97a9; }.row-editor { max-height:460px; overflow:auto; background:#f8fafd; border:1px solid #e3eaf3; padding:14px; border-radius:6px; }.row-editor h4 { margin:0 0 9px; }.row-editor label { display:block; font-size:12px; color:#6c7e96; margin:9px 0; }.row-editor label > span { display:block; margin-bottom:4px; }.row-editor label .el-input,.row-editor .el-select { margin-top:4px; }.issues { font-size:12px; color:#a5741b; line-height:1.5; }.issues p { margin:4px 0; }.target-fields { display:grid; grid-template-columns:1fr 1fr; gap:0 10px; }.el-select { width:100%; }pre { white-space:pre-wrap; overflow-wrap:anywhere; font-size:11px; }.footer-note { margin-right:14px; }
@media(max-width:1000px) { .import-layout,.mapping-grid { grid-template-columns:1fr; }.row-editor { max-height:400px; }.footer-note { display:block; margin:8px 0; } }
</style>
