<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { catalogSourceDetail, catalogSources, changeCatalogSourcePurpose, createCatalogSource, previewCatalogSourcePurpose } from '@/api/modules/partnerCatalog'
import { fileBlob, newRequestId, partnerOptions, saveBlob } from '@/api/modules/partner'
import type { CatalogPage, CatalogPurposePreview, CatalogSource } from '@/types/partnerCatalog'
import type { PartnerFile } from '@/types/partner'
import { partnerError } from '@/utils/partnerError'
import SelectionFileTasks from '../selection/SelectionFileTasks.vue'
import CatalogOnlineRows from './CatalogOnlineRows.vue'

const props = defineProps<{ modelValue: boolean; supplierCode?: string; canWrite: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; changed: [] }>()
const suppliers = ref<{ code: string; name: string }[]>([]), supplier = ref(''), name = ref(''), purpose = ref<'catalog_source' | 'historical'>('catalog_source')
const sources = ref<CatalogPage<CatalogSource>>(), page = ref(1), loading = ref(false), busy = ref(false), error = ref('')
const source = ref<CatalogSource>(), fileOpen = ref(false), reason = ref(''), candidate = ref<CatalogSource>(), purposePreview = ref<CatalogPurposePreview>(), acknowledged = ref(false)
const onlineOpen=ref(false)
const createRequest = ref(''), purposeRequest = ref('')
const downloadError = ref(''), failedDownload = ref<PartnerFile>(), downloading = ref(false)
let generation = 0, listGeneration = 0
const selectedSupplierName = computed(() => suppliers.value.find(item => item.code === supplier.value)?.name || '')
const canCreate = computed(() => props.canWrite && !!supplier.value && !!name.value.trim() && !busy.value)
const canConfirmPurpose = computed(() => props.canWrite && purposePreview.value?.canChange && acknowledged.value && !!reason.value.trim() && !busy.value)
const originals = computed(() => source.value?.files?.filter(file => /\.(xlsx|xls|pdf)$/i.test(file.name)) || [])
async function loadSources() {
  const serial = ++listGeneration
  error.value = ''
  if (!supplier.value) { sources.value = undefined; loading.value = false; return }
  loading.value = true
  try {
    const data = await catalogSources({ supplierCode: supplier.value, purpose: purpose.value, page: page.value, size: 20 })
    if (serial === listGeneration && props.modelValue) sources.value = data
  } catch (cause) { if (serial === listGeneration) error.value = partnerError(cause) }
  finally { if (serial === listGeneration) loading.value = false }
}
watch(() => props.modelValue, async open => {
  const serial = ++generation
  listGeneration++; error.value = ''; source.value = undefined; fileOpen.value = false; onlineOpen.value=false; candidate.value = undefined; purposePreview.value = undefined; downloadError.value = ''; failedDownload.value = undefined
  if (!open) return
  supplier.value = props.supplierCode || ''; name.value = ''; purpose.value = 'catalog_source'; page.value = 1; createRequest.value = newRequestId()
  try {
    const options = await partnerOptions()
    if (serial !== generation || !props.modelValue) return
    suppliers.value = options.suppliers.map(item => ({ code: String(item.code), name: String(item.name) }))
    if (!name.value) name.value = selectedSupplierName.value ? `${selectedSupplierName.value}资料` : ''
    await loadSources()
  } catch (cause) { if (serial === generation) error.value = partnerError(cause) }
},{immediate:true})
watch([supplier, purpose], () => { page.value = 1; candidate.value = undefined; purposePreview.value = undefined; if (props.modelValue) void loadSources() })
watch([supplier, name], () => { createRequest.value = newRequestId() })
watch([reason, candidate], () => { purposePreview.value = undefined; acknowledged.value = false; purposeRequest.value = newRequestId() })
onBeforeUnmount(() => { generation++; listGeneration++ })
function supplierChanged() { name.value = selectedSupplierName.value ? `${selectedSupplierName.value}资料` : '' }
function close() { if (!busy.value&&!fileOpen.value&&!onlineOpen.value) emit('update:modelValue', false) }
async function openSource(value: CatalogSource,mode:'file'|'online'='file') {
  if(busy.value)return
  const serial=generation;busy.value=true;error.value=''
  try{const data=await catalogSourceDetail(value.id);if(serial===generation&&props.modelValue){source.value=data;downloadError.value='';failedDownload.value=undefined;fileOpen.value=mode==='file';onlineOpen.value=mode==='online'}}
  catch(cause){if(serial===generation)error.value=partnerError(cause)}finally{busy.value=false}
}
async function create(mode:'file'|'online'='file') {
  if (!canCreate.value) return
  const serial = generation
  busy.value = true; error.value = ''
  try {
    const data = await createCatalogSource({ name: name.value.trim(), supplierCode: supplier.value, requestId: createRequest.value })
    if (serial !== generation || !props.modelValue) return
    source.value=data;fileOpen.value=mode==='file';onlineOpen.value=mode==='online';await loadSources()
  } catch (cause) { if (serial === generation) error.value = partnerError(cause) }
  finally { busy.value = false }
}
function preparePurpose(value: CatalogSource) { candidate.value = value; reason.value = ''; purposePreview.value = undefined; acknowledged.value = false; purposeRequest.value = newRequestId() }
async function previewPurpose() {
  if (!candidate.value || !props.canWrite || !reason.value.trim() || busy.value) return
  const serial = generation, id = candidate.value.id
  busy.value = true; error.value = ''
  try {
    const data = await previewCatalogSourcePurpose(id, { version: candidate.value.version, purpose: 'catalog_source', reason: reason.value.trim() })
    if (serial === generation && props.modelValue && id === candidate.value?.id) purposePreview.value = data
  } catch (cause) { if (serial === generation) error.value = partnerError(cause) }
  finally { busy.value = false }
}
async function confirmPurpose() {
  if (!candidate.value || !purposePreview.value || !canConfirmPurpose.value) return
  const serial = generation
  busy.value = true; error.value = ''
  try {
    await changeCatalogSourcePurpose(candidate.value.id, { version: candidate.value.version, purpose: 'catalog_source', reason: reason.value.trim(), requestId: purposeRequest.value, previewToken: purposePreview.value.previewToken })
    if (serial !== generation || !props.modelValue) return
    candidate.value = undefined; emit('changed'); await loadSources()
  } catch (cause) { if (serial === generation) error.value = partnerError(cause) }
  finally { busy.value = false }
}
async function filesChanged() { emit('changed'); await loadSources(); if(source.value){try{source.value=await catalogSourceDetail(source.value.id)}catch(cause){error.value=partnerError(cause)}} }
async function download(file: PartnerFile) {
  if(downloading.value)return
  const serial=generation,sourceId=source.value?.id
  downloading.value=true;downloadError.value='';failedDownload.value=undefined
  try{const blob=await fileBlob(file.id,false);if(serial===generation&&sourceId===source.value?.id&&props.modelValue)saveBlob(blob,file.name)}
  catch(cause){if(serial===generation&&sourceId===source.value?.id&&props.modelValue){downloadError.value=partnerError(cause);failedDownload.value=file}}
  finally{downloading.value=false}
}
</script>

<template>
  <el-dialog :model-value="modelValue&&!fileOpen&&!onlineOpen" class="catalog-source-dialog" :title='uiText("供应商资料与导入")' width="min(880px,96vw)" top="20px" append-to-body :close-on-click-modal="!busy" :show-close="!busy" @close="close">
    <div class="catalog-source-management">
      <el-alert v-if="error" :title="error" type="error" :closable="false"/>
      <p class="source-intro">{{ uiText("先保留供应商有什么，再从目录挑选本次需要的款式和具体型号；导入不代表我方已选。") }}</p>
      <el-select v-model="supplier" filterable clearable :aria-label='uiText("资料所属供应商")' :placeholder='uiText("选择已有供应商")' :disabled="busy" @change="supplierChanged"><el-option v-for="item in suppliers" :key="item.code" :value="item.code" :label="item.name"/></el-select>
      <el-radio-group v-model="purpose" :disabled="busy" :aria-label='uiText("来源资料范围")'><el-radio-button value="catalog_source">{{ uiText("供应商资料") }}</el-radio-button><el-radio-button value="historical">{{ uiText("历史资料整理") }}</el-radio-button></el-radio-group>
      <form v-if="purpose==='catalog_source'&&canWrite" class="source-create" @submit.prevent="create()"><el-input v-model="name" :placeholder='uiText("本次资料名称")' :aria-label='uiText("供应商资料名称")' :disabled="busy" maxlength="120"/><el-button type="primary" native-type="submit" :disabled="!canCreate" :loading="busy">{{ uiText("新建资料并上传") }}</el-button><el-button :disabled="!canCreate" @click="create('online')">{{ uiText("新建并在线录入") }}</el-button></form>
      <p v-if="purpose==='historical'" class="source-intro">{{ uiText("只整理明确属于供应商全量候选的旧资料。逐份预览、明确确认；不删除行、图片或历史，不自动创建产品／样品。") }}</p>
      <div class="source-list" :aria-busy="loading"><article v-for="item in sources?.content||[]" :key="item.id" class="source-record"><div><strong>{{item.name}}</strong><small>{{item.supplierName}} · {{item.rowCount}}{{ uiText(" 款") }}<template v-if="item.sourceKind==='selection'">{{ uiText(" · 历史资料") }}</template></small><span>{{item.code}} · V{{item.version}}</span></div><el-button v-if="purpose==='catalog_source'" :disabled="busy" @click="openSource(item)">{{item.canImport?uiText('继续上传／导入'):uiText('查看历史导入')}}</el-button><el-button v-if="purpose==='catalog_source'&&item.canImport&&item.kind==='supplier_quote'" :disabled="busy" @click="openSource(item,'online')">{{ uiText("在线录入／粘贴") }}</el-button><el-button v-else-if="purpose==='historical'&&canWrite" :disabled="busy" @click="preparePurpose(item)">{{ uiText("核对用途") }}</el-button></article><el-empty v-if="!loading&&!sources?.content.length" :description="supplier?uiText('当前范围暂无资料'):uiText('请先选择供应商，再查看或导入资料')" :image-size="45"/><p v-if="loading" role="status">{{ uiText("正在读取资料…") }}</p></div>
      <el-pagination v-if="(sources?.totalElements||0)>20" v-model:current-page="page" :page-size="20" :total="sources?.totalElements||0" layout="total,prev,pager,next" :disabled="busy||loading" @current-change="loadSources"/>
      <section v-if="candidate" class="source-purpose-preview"><h3>{{ uiText("整理用途：") }}{{candidate.name}}</h3><p>{{candidate.code}} · V{{candidate.version}} · {{candidate.rowCount}}{{ uiText(" 款。仅将本资料归入供应商候选目录，原行与图片不变，不代替工程选品。") }}</p><el-input v-model="reason" type="textarea" autosize :aria-label='uiText("资料用途整理原因")' :placeholder='uiText("请填写核对依据或整理原因")' :disabled="busy"/><el-button :disabled="!reason.trim()||busy" @click="previewPurpose">{{ uiText("预览用途调整") }}</el-button><template v-if="purposePreview"><p v-for="message in purposePreview.messages" :key="message" class="source-issue">{{message}}</p><el-checkbox v-if="purposePreview.canChange" v-model="acknowledged" :disabled="busy">{{ uiText("已核对本份为供应商候选资料，不是我方已选清单；仅调整用途，原件、图片及行身份保留") }}</el-checkbox><el-button type="primary" :disabled="!canConfirmPurpose" :loading="busy" @click="confirmPurpose">{{ uiText("确认整理为供应商资料") }}</el-button></template><el-button :disabled="busy" @click="candidate=undefined">{{ uiText("取消本份整理") }}</el-button></section>
    </div>
    <template #footer><el-button :disabled="busy" @click="close">{{ uiText("返回目录") }}</el-button></template>
  </el-dialog>
  <CatalogOnlineRows v-if="source&&onlineOpen" :source="source" :suppliers="suppliers" :can-write="canWrite" @close="onlineOpen=false" @changed="filesChanged"/>
  <template v-if="source">
    <SelectionFileTasks :key="source.id" v-model="fileOpen" context="catalog" :id="source.id" mode="import" :suppliers="suppliers" :supplier-code="source.supplierCode||supplier" :can-write="canWrite&&source.canImport===true" @changed="filesChanged"><template #source-files><div v-if="originals.length" class="source-originals"><strong>{{ uiText("来源原件") }}</strong><el-button v-for="file in originals" :key="file.id" link :disabled="downloading" @click="download(file)">{{file.name}} · {{file.version}}</el-button></div><el-alert v-if="failedDownload" class="source-download-error" :title='uiText("原件下载失败")' type="error" :closable="false"><span>{{failedDownload.name}}：{{downloadError}}</span><el-button link :disabled="downloading" :aria-label="uiText('重试下载')+failedDownload.name" @click="download(failedDownload)">{{ uiText("重试下载") }}</el-button></el-alert><p v-if="!source.canImport" class="source-intro">{{ uiText("历史来源只读，原件及导入记录保留。新版资料请返回后新建供应商资料。") }}</p></template></SelectionFileTasks>
  </template>
</template>

<style scoped>
:global(.catalog-source-dialog){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-bottom:20px}:global(.catalog-source-dialog>.el-dialog__body){overflow:auto;min-height:0}:global(.catalog-source-dialog>.el-dialog__header),:global(.catalog-source-dialog>.el-dialog__footer){flex:none}
.catalog-source-management{display:flex;flex-direction:column;gap:10px;min-width:0}.source-intro{font-size:13px;line-height:1.6;color:var(--el-text-color-secondary);margin:0;overflow-wrap:anywhere}.source-create{display:flex;gap:8px}.source-create>.el-input{flex:1;min-width:0}.source-list{border:1px solid var(--el-border-color-lighter);border-radius:5px}.source-record{display:flex;align-items:center;gap:10px;padding:9px;border-bottom:1px solid var(--el-border-color-lighter);min-width:0}.source-record:last-child{border:0}.source-record>div{flex:1;min-width:0;overflow-wrap:anywhere}.source-record strong{font-size:14px}.source-record small,.source-record span{display:block;font-size:12px;line-height:1.5;color:var(--el-text-color-secondary);margin-top:2px}.source-purpose-preview{border:1px solid var(--el-color-warning-light-5);padding:10px;border-radius:5px;display:flex;flex-direction:column;gap:8px}.source-purpose-preview h3{font-size:14px;margin:0}.source-purpose-preview p{font-size:13px;line-height:1.6;margin:0}.source-issue{color:var(--el-color-warning-dark-2)}.source-purpose-preview :deep(.el-checkbox){height:auto;white-space:normal}.source-purpose-preview :deep(.el-checkbox__label){white-space:normal;line-height:1.5}.catalog-source-management :deep(.el-button){white-space:normal;height:auto;min-height:30px;line-height:1.5;overflow-wrap:anywhere}@media(max-width:600px){.source-create{flex-wrap:wrap}.source-create>.el-input{flex-basis:100%}.source-record{align-items:flex-start;flex-wrap:wrap}}
</style>
