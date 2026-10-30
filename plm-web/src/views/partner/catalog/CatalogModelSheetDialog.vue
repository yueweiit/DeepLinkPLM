<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import PhoneModelMultiSelect from '@/components/PhoneModelMultiSelect/index.vue'
import { newRequestId } from '@/api/modules/partner'
import { previewCatalogModelSheet, saveCatalogModelSheet, uploadCatalogModelSheetFile } from '@/api/modules/partnerCatalog'
import type { CatalogModelEntry, CatalogModelEntryInput, CatalogModelSheetInput, CatalogModelSheetPreview, CatalogModelSheetResult, CatalogStyle } from '@/types/partnerCatalog'
import type { PartnerFile } from '@/types/partner'
import type { CompatiblePhone } from '@/types/molds'
import { MAX_UPLOAD_FILE_SIZE_BYTES, formatFileSize } from '@/utils/file'
import { partnerError } from '@/utils/partnerError'
import PartnerFiles from '../PartnerFiles.vue'
import { catalogIdentity } from './catalogPresentation'
import { catalogModelIndexDisplay, catalogModelEntryInput, catalogModelEntryKey, modelSheetIssues, parseCatalogModelSheet } from './catalogModelSheet'

const props = defineProps<{ row: CatalogStyle }>()
const emit = defineEmits<{ close: []; saved: [result: CatalogModelSheetResult] }>()
const entries = ref<CatalogModelEntryInput[]>((props.row.supplierModelEntries || []).map(entry => { const input = catalogModelEntryInput(entry); if (entry.matchMethod !== 'manual' && entry.matchState !== 'manual') delete input.phoneModelIds; return input }))
const manualPhones = ref<Record<number, CompatiblePhone[]>>({})
const refs = ref((props.row.modelSheetRefs || []).map(({ id }) => ({ id })))
const files = ref<PartnerFile[]>([...props.row.modelSheetFileDetails || []])
const pasteText = ref(''), pasteOpen = ref(!entries.value.length), page = ref(1), error = ref(''), preview = ref<CatalogModelSheetPreview>()
const previewing = ref(false), saving = ref(false), uploading = ref(false), stale = ref(false), acknowledged = ref(false)
const requestId = ref(newRequestId()), retryFile = ref<File>(), uploadRequestIds = new Map<string, string>()
const busy = computed(() => previewing.value || saving.value || uploading.value)
const writable = computed(() => props.row.canMaintainModelSheet === true && !stale.value)
const initial = JSON.stringify({ entries: entries.value, refs: refs.value })
const dirty = computed(() => !!pasteText.value.trim() || JSON.stringify({ entries: entries.value, refs: refs.value }) !== initial)
const payload = computed<CatalogModelSheetInput>(() => ({ ...catalogIdentity(props.row), supplierCode: props.row.supplierCode, entries: entries.value.map(catalogModelEntryInput), modelSheetRefs: refs.value.map(({ id }) => ({ id })) }))
const visible = computed(() => (preview.value?.entries || entries.value).map((entry, index) => ({ entry, index })).slice((page.value - 1) * 20, page.value * 20))
const canSave = computed(() => writable.value && !busy.value && preview.value?.canSave === true && !!preview.value.previewToken && acknowledged.value)
function describeEntry(entry?:CatalogModelEntry){if(!entry)return '原来没有此条目';const names=entry.phoneModelIds.map(id=>entry.phones?.find(phone=>phone.phoneModelId===id)||props.row.phones.find(phone=>phone.phoneModelId===id)).map(phone=>phone?`${phone.modelName||phone.displayName}${phone.phoneModelCode?' · '+phone.phoneModelCode:''}`:'关联资料待加载');return [entry.rawBrandGroup,entry.rawModelText,entry.rawStatus||'原表未注明进度',entry.rawNote,names.length?names.join('、'):'尚未关联'].filter(Boolean).join('；')}
const differences=computed(()=>{const before=preview.value?.before?.supplierModelEntries||[];return (preview.value?.after?.supplierModelEntries||preview.value?.entries||[]).flatMap(entry=>{const old=before.find(item=>catalogModelEntryKey(item)===catalogModelEntryKey(entry));return JSON.stringify(catalogModelEntryInput(old||entry))===JSON.stringify(catalogModelEntryInput(entry))&&old?[]:[{key:catalogModelEntryKey(entry),previous:describeEntry(old),next:describeEntry(entry)}]})})
const removedNames=computed(()=>(preview.value?.removedPhoneModelIds||[]).map(id=>{const phone=props.row.phones.find(value=>value.phoneModelId===id);return phone?`${phone.modelName||phone.displayName}${phone.phoneModelCode?' · '+phone.phoneModelCode:''}`:'原关联型号资料待加载'}).join('、'))
let generation = 0, closing = false, saved = false
watch(payload, () => { generation++; preview.value = undefined; acknowledged.value = false; requestId.value = newRequestId(); error.value = '' }, { deep: true })
watch(() => [props.row.sourceKind, props.row.recordId, props.row.rowId, props.row.rowVersion, props.row.recordVersion], () => {
  generation++; stale.value = true; preview.value = undefined; error.value = '来源版本已变化，请关闭后重新打开本款核对。'
})
function applyPaste() {
  if (!writable.value || busy.value) return
  try {
    const parsed = parseCatalogModelSheet(pasteText.value)
    if (!parsed.length) throw new Error('请先粘贴原始型号条目。')
    const old = props.row.supplierModelEntries || []
    if (old.some(entry => !parsed.some(next => catalogModelEntryKey(next) === catalogModelEntryKey(entry)))) throw new Error('本次缺少已保存的原始条目；请保留全部原记录，不把漏行当作删除。')
    entries.value = parsed; pasteText.value = ''; page.value = 1; pasteOpen.value = false; error.value = ''
  } catch (cause) { error.value = partnerError(cause) }
}
function matchChanged(index: number) { delete entries.value[index].phoneModelIds }
function selectManual(index: number, ids: number[]) { if (!writable.value || busy.value) return; entries.value[index].phoneModelIds = [...ids] }
function resetManual(index: number) { if (!writable.value || busy.value) return; delete entries.value[index].phoneModelIds; delete manualPhones.value[index] }
function rememberManual(index: number, members: CompatiblePhone[]) { manualPhones.value[index] = members }
async function check() {
  if (!writable.value || busy.value || !entries.value.length || pasteText.value.trim()) return
  const serial = generation, input = payload.value, signature = JSON.stringify(input)
  previewing.value = true; error.value = ''
  try {
    const result = await previewCatalogModelSheet(input)
    if (serial !== generation || signature !== JSON.stringify(payload.value)) return
    if (result.entries.length !== input.entries.length || result.summary.entryCount !== input.entries.length) throw new Error('预览条目数与原始记录不一致，已停止确认；请核对后重试，不能丢行保存。')
    preview.value = result; acknowledged.value = false; page.value = 1
  } catch (cause) { if (serial === generation) error.value = partnerError(cause) }
  finally { previewing.value = false }
}
async function save() {
  if (!canSave.value) return
  const serial = generation
  saving.value = true; error.value = ''
  try {
    const result = await saveCatalogModelSheet({ ...payload.value, requestId: requestId.value, previewToken: preview.value!.previewToken })
    if (serial !== generation) return
    saved = true; emit('saved', result)
  } catch (cause) { if (serial === generation) error.value = `${partnerError(cause)}。本次原始条目与图片仍保留，可核对后重试。` }
  finally { saving.value = false }
}
async function upload(file: File) {
  if (!writable.value || busy.value) return false
  if (!/\.(png|jpe?g|webp)$/i.test(file.name)) { error.value = '型号表原图只接受PNG、JPG或WebP；不要在此上传整份报价文件。'; return false }
  if (file.size > MAX_UPLOAD_FILE_SIZE_BYTES) { error.value = `单张原图不能超过 ${formatFileSize(MAX_UPLOAD_FILE_SIZE_BYTES)}，请保留实际型号表图片。`; return false }
  const serial = generation, identity = { ...catalogIdentity(props.row), supplierCode: props.row.supplierCode }
  uploading.value = true; error.value = ''; retryFile.value = file
  try {
    const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
    const sha256 = [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, '0')).join('')
    if (serial !== generation || !writable.value) return false
    const key = `plm-catalog-model-photo-${identity.sourceKind}-${identity.recordId}-${encodeURIComponent(identity.rowId)}-${sha256}`
    let previous = uploadRequestIds.get(key)
    try { previous ||= localStorage.getItem(key) || undefined } catch { /* In-memory idempotency still protects retries in this dialog. */ }
    const id = previous || newRequestId(); uploadRequestIds.set(key, id)
    try { localStorage.setItem(key, id) } catch { /* Storage may be disabled; never change the server contract. */ }
    const attachment = await uploadCatalogModelSheetFile({ ...identity, requestId: id, sha256 }, file)
    if (serial !== generation || !writable.value) return false
    if (!refs.value.some(ref => ref.id === attachment.attachmentId)) refs.value.push({ id: attachment.attachmentId })
    files.value = [...files.value.filter(item => item.id !== attachment.attachmentId), { id: attachment.attachmentId, name: attachment.originalFileName || file.name, version: attachment.versionNo || 'V1', type: attachment.contentType || file.type || 'image/png', size: attachment.fileSize }]
    retryFile.value = undefined
  } catch (cause) { if (serial === generation) error.value = `${partnerError(cause)}。上传结果可能尚未确认；请重试同一原图，不会重建目录款式。` }
  finally { uploading.value = false }
  return false
}
async function canLeave() {
  if (busy.value || closing) return false
  if (saved || !dirty.value) return true
  closing = true
  try { await ElMessageBox.confirm(uiText('本次型号表尚未确认保存，确定离开？已上传原图会保留，但不会自动关联到其他款式。'), uiText('保留本次核对'), { confirmButtonText: uiText('放弃并离开'), cancelButtonText: uiText('继续核对'), closeOnClickModal: false }); return true }
  catch { return false }
  finally { closing = false }
}
async function close(done?: () => void) { if (await canLeave()) { done?.(); emit('close') } }
function beforeUnload(event: BeforeUnloadEvent) { if (!saved && (dirty.value || busy.value)) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(canLeave); onBeforeRouteUpdate(canLeave)
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => { generation++; window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <el-dialog :model-value="true" :title='uiText("本款型号表核对")' width="min(1120px,96vw)" top="20px" class="catalog-model-sheet-dialog" append-to-body :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="close">
    <div class="model-sheet-content">
      <p class="model-sheet-intro"><strong>{{ row.supplierName }} · {{ row.name }}</strong>{{ uiText("　只更新本款型号依据，不新增产品、样品或手机编码。") }}</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false"/>
      <section class="model-sheet-evidence"><header><h3>{{ uiText("型号表原图") }}</h3><el-upload v-if="row.canMaintainModelSheet" :show-file-list="false" :before-upload="upload" accept=".png,.jpg,.jpeg,.webp" :disabled="!writable||busy"><el-button :loading="uploading" :disabled="!writable||busy">{{ uiText("上传型号表图片") }}</el-button></el-upload><el-button v-if="row.canMaintainModelSheet&&retryFile" :disabled="!writable||busy" @click="upload(retryFile)">{{ uiText("重试同一原图") }}</el-button></header><p>{{ uiText("只上传本款型号表；整份报价原件保持原权限，不作为普通图片共享。") }}</p><PartnerFiles v-if="files.length" kind="partner-selections" :id="row.recordId" :files="files" :model-value="refs" :can-write="false" :can-write-money="false"/><p v-else class="muted">{{row.canMaintainModelSheet?uiText('可直接上传来源型号表图片；上传不代表已确认关联。'):uiText('暂无已关联型号表图片。')}}</p></section>
      <details v-if="row.canMaintainModelSheet" :open="pasteOpen" class="model-sheet-paste" @toggle="pasteOpen=($event.target as HTMLDetailsElement).open"><summary>{{ uiText("从Excel粘贴原始条目") }}</summary><p>{{ uiText("按列粘贴：原序号、品牌分组、原型号、原状态、原备注；后面可加“匹配用名称、匹配说明”。保留空白列和斜杠组合，不按一行创建一个款式。") }}</p><el-input v-model="pasteText" type="textarea" :rows="5" :aria-label='uiText("粘贴本款型号表")' :disabled="!writable||busy" :placeholder='uiText("原序号　品牌分组　原型号　原状态　原备注")'/><el-button :disabled="!writable||busy||!pasteText.trim()" @click="applyPaste">{{ uiText("读取本次条目") }}</el-button></details>
      <div class="model-sheet-summary"><strong>{{ entries.length }}{{ uiText(" 条原记录") }}</strong><span v-if="preview">{{ uiText("关联 ") }}{{ preview.summary.phoneCount }}{{ uiText(" 个现有型号 · 按名称匹配 ") }}{{preview.summary.matchedEntryCount}}{{ uiText(" 条") }}<template v-if="preview.summary.manualEntryCount">{{ uiText(" · 人工核对 ") }}{{preview.summary.manualEntryCount}}{{ uiText(" 条") }}</template> · {{ preview.summary.unresolvedEntryCount }}{{ uiText(" 条保留待关联") }}</span><span v-else>{{ uiText("原状态如实保留；匹配结果以服务器预览为准。") }}</span></div>
      <p v-for="message in preview?.messages||[]" :key="message" class="model-sheet-message">{{ message }}</p>
      <section v-if="preview?.before&&preview.after" class="model-sheet-delta"><strong>{{ uiText("本款变更：原始条目 ") }}{{preview.before.supplierModelEntries.length}} → {{preview.after.supplierModelEntries.length}}{{ uiText("；关联型号 ") }}{{preview.before.phoneModelIds.length}} → {{preview.after.phoneModelIds.length}}{{ uiText("；型号表原图 ") }}{{preview.before.modelSheetRefs.length}} → {{preview.after.modelSheetRefs.length}}</strong><p>{{ uiText("旧型号表的关联按本次核对调整，原图保留，不影响其他款式。") }}</p><p v-if="preview.preservedIndependentPhoneModelIds||preview.removedPhoneModelIds" class="model-sheet-scope-delta">{{ uiText("保留原独立关联 ") }}{{preview.preservedIndependentPhoneModelIds?.length||0}}{{ uiText(" 个；本次移除旧关联 ") }}{{preview.removedPhoneModelIds?.length||0}}{{ uiText(" 个。") }}<span v-if="removedNames">{{ uiText("移除：") }}{{removedNames}}</span></p><details v-if="differences.length"><summary>{{ uiText("查看 ") }}{{differences.length}}{{ uiText(" 条前后差异") }}</summary><article v-for="change in differences" :key="change.key"><p><b>{{ uiText("原来：") }}</b>{{change.previous}}</p><p><b>{{ uiText("本次：") }}</b>{{change.next}}</p></article></details></section>
      <div class="model-sheet-table">
        <article v-for="{entry,index} in visible" :key="index" class="model-sheet-entry">
          <div class="model-sheet-raw"><span class="model-sheet-index">{{ catalogModelIndexDisplay(entry.supplierIndex).label }}</span><div><small>{{ entry.rawBrandGroup }}</small><strong>{{ entry.rawModelText }}</strong></div><span class="model-sheet-state">{{ entry.rawStatus||uiText('原表未注明进度') }}</span><details v-if="entry.rawNote||catalogModelIndexDisplay(entry.supplierIndex).sourceLocation" class="model-sheet-source-location"><summary>{{ uiText("核对说明") }}</summary><p v-if="catalogModelIndexDisplay(entry.supplierIndex).sourceLocation">{{ catalogModelIndexDisplay(entry.supplierIndex).sourceLocation }}</p><p v-if="entry.rawNote">{{ entry.rawNote }}</p></details></div>
          <div class="model-sheet-linked">
            <template v-if="preview"><small>{{preview.entries[index].matchMethod==='manual'?uiText('工程明确关联'):uiText('按名称核对结果')}}</small><div v-for="phone in preview.entries[index].phones||[]" :key="phone.phoneModelId"><strong>{{ phone.modelName||phone.displayName }}</strong><span>{{ phone.brandName }} · {{ phone.phoneModelCode||uiText('编码未分配') }}</span></div><p v-if="!preview.entries[index].phones?.length">{{ uiText("原文待关联") }}</p><p v-for="issue in modelSheetIssues(preview.entries[index])" :key="issue" class="model-sheet-message">{{ issue }}</p></template>
            <details v-if="row.canMaintainModelSheet" class="model-sheet-correction"><summary>{{ uiText("需要时核对匹配／人工关联") }}</summary><p>{{ uiText("原型号保持不变；名称有歧义时，可明确关联已有手机资料，不创建或拆分手机编码。") }}</p><el-input v-model="entries[index].matchText" :aria-label="uiText('原表{value0}匹配用名称',{value0:(entry.supplierIndex)})" :disabled="!writable||busy" :placeholder='uiText("匹配用名称（选填）")' @input="matchChanged(index)"/><PhoneModelMultiSelect :model-value="entries[index].phoneModelIds||[]" :members="manualPhones[index]||preview?.entries[index]?.phones||row.supplierModelEntries?.[index]?.phones||[]" :disabled="!writable||busy" :aria-label="uiText('原表{value0}人工关联手机型号',{value0:(entry.supplierIndex)})" :placeholder='uiText("按需人工关联现有手机型号")' @update:model-value="selectManual(index,$event)" @selected="rememberManual(index,$event)"/><el-button v-if="entries[index].phoneModelIds!==undefined" link :disabled="!writable||busy" @click="resetManual(index)">{{ uiText("恢复按名称核对") }}</el-button><el-input v-model="entries[index].matchNote" :aria-label="uiText('原表{value0}匹配说明',{value0:(entry.supplierIndex)})" :disabled="!writable||busy" type="textarea" autosize :placeholder='uiText("人工关联或更正匹配名称时说明核对依据")'/></details>
          </div>
        </article>
        <el-empty v-if="!entries.length" :description='uiText("请先粘贴本款原始型号条目")' :image-size="55"/>
      </div>
      <el-pagination v-if="entries.length>20" v-model:current-page="page" :page-size="20" :total="entries.length" :pager-count="5" layout="total,prev,pager,next"/>
      <el-checkbox v-if="preview?.canSave" v-model="acknowledged" class="model-sheet-ack" :disabled="busy||!writable">{{ uiText("已逐项核对原图及全部条目；未匹配项按原文保留，不代表现货、实收或质检通过") }}</el-checkbox>
    </div>
    <template #footer><el-button :disabled="busy" @click="close()">{{ uiText("返回本款") }}</el-button><el-button v-if="row.canMaintainModelSheet" :disabled="!writable||busy||!entries.length||!!pasteText.trim()" :loading="previewing" @click="check">{{ uiText("预览型号关联") }}</el-button><el-button v-if="row.canMaintainModelSheet" type="primary" :disabled="!canSave" :loading="saving" @click="save">{{ uiText("确认仅更新本款") }}</el-button></template>
  </el-dialog>
</template>

<style scoped>
.model-sheet-delta{margin:8px 0;padding:8px;background:var(--el-color-primary-light-9);border-radius:5px;overflow-wrap:anywhere}.model-sheet-delta details>summary{cursor:pointer;color:#34597d}.model-sheet-delta details>article{border-top:1px solid var(--el-border-color-lighter);padding:4px 0}
:global(.catalog-model-sheet-dialog){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-bottom:20px}:global(.catalog-model-sheet-dialog>.el-dialog__body){min-height:0;overflow-y:auto}:global(.catalog-model-sheet-dialog>.el-dialog__footer){display:flex;justify-content:flex-end;gap:7px;flex-wrap:wrap}.model-sheet-content{min-width:0;container-type:inline-size;font-size:13px;line-height:1.6}.model-sheet-content p{margin:5px 0;overflow-wrap:anywhere}.model-sheet-intro{margin-top:0!important}.model-sheet-evidence{border:1px solid var(--el-border-color-lighter);border-radius:5px;padding:8px;margin:9px 0}.model-sheet-evidence>header{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.model-sheet-evidence h3{font-size:14px;margin:0;flex:1}.model-sheet-evidence :deep(.partner-files>header){display:none}.model-sheet-evidence>p,.muted{color:var(--el-text-color-secondary)}.model-sheet-paste{margin:8px 0}.model-sheet-paste>summary,.model-sheet-correction>summary{cursor:pointer;color:#34597d}.model-sheet-paste>.el-button{margin-top:6px}.model-sheet-summary{display:flex;gap:5px 14px;flex-wrap:wrap;margin:9px 0}.model-sheet-entry{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.2fr);gap:10px;padding:8px;border-top:1px solid var(--el-border-color-lighter);min-width:0}.model-sheet-entry:first-child{border-top:0}.model-sheet-table{border:1px solid var(--el-border-color-lighter);border-radius:5px}.model-sheet-raw{display:grid;grid-template-columns:40px minmax(0,1fr);gap:2px 8px;align-content:start;min-width:0}.model-sheet-raw>div{display:flex;flex-direction:column;min-width:0;overflow-wrap:anywhere}.model-sheet-raw strong{font-size:15px;color:#284d7c}.model-sheet-raw small{font-size:12px;color:var(--el-text-color-secondary)}.model-sheet-index{color:var(--el-text-color-secondary)}.model-sheet-state,.model-sheet-raw>p,.model-sheet-source-location{grid-column:2;overflow-wrap:anywhere}.model-sheet-state{font-weight:600}.model-sheet-linked{min-width:0;overflow-wrap:anywhere}.model-sheet-linked>div{display:flex;gap:4px 10px;flex-wrap:wrap}.model-sheet-linked>div>span{color:var(--el-text-color-secondary);font-size:12px}.model-sheet-message{color:var(--el-color-warning-dark-2)}.model-sheet-correction{margin-top:5px}.model-sheet-correction .el-input{margin-top:4px}.model-sheet-correction p{font-size:12px;color:var(--el-text-color-secondary)}.model-sheet-content>.el-pagination{margin:8px 0;flex-wrap:wrap}.model-sheet-ack{height:auto;max-width:100%;margin:8px 0 0}.model-sheet-ack :deep(.el-checkbox__label){white-space:normal;overflow-wrap:anywhere;line-height:1.6}.model-sheet-content :deep(.el-button){max-width:100%;height:auto;min-height:28px;white-space:normal;overflow-wrap:anywhere;line-height:1.5}@container(max-width:650px){.model-sheet-entry{grid-template-columns:minmax(0,1fr)}.model-sheet-linked{padding-left:48px}}
</style>
