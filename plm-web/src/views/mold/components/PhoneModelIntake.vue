<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import FixedTableViewport from '@/components/FixedTableViewport/index.vue'
import PhoneModelMultiSelect from '@/components/PhoneModelMultiSelect/index.vue'
import * as api from '@/api/modules/phoneModelIntake'
import { getPhoneSelectionBrands, getPhoneModelOptions } from '@/api/modules/phoneReference'
import { newRequestId } from '@/api/modules/partner'
import { useUserStore } from '@/stores/user'
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace'
import type { CompatiblePhone, PhoneBrand } from '@/types/molds'
import type { PhoneIntakeAction, PhoneIntakeCandidate, PhoneIntakeClassification, PhoneIntakeDetail, PhoneIntakePage, PhoneIntakeResolutionInput, PhoneIntakeResolutionPreview, PhoneIntakeSource, PhoneIntakeSourceInput, PhoneIntakeSourcePreview, PhoneIntakeState } from '@/types/phoneModelIntake'
import { catalogModelIndexDisplay } from '@/views/partner/catalog/catalogModelSheet'
import { parsePhoneIntakeSource, phoneIntakeClassifications, phoneIntakeReviewReasons, phoneIntakeStates } from '@/utils/phoneModelIntake'
import { partnerError } from '@/utils/partnerError'
import { phoneMemberName } from '@/utils/phoneMembers'
import PartnerFiles from '@/views/partner/PartnerFiles.vue'
import PhoneIntakeSourcePicker from './PhoneIntakeSourcePicker.vue'

const props = defineProps<{ visible: boolean; editable: boolean; keyword?: string }>()
const emit = defineEmits<{ changed: [] }>()
const user = useUserStore(), workspace = useCollaborationWorkspace()
const keyword = ref(props.keyword || ''), state = ref<PhoneIntakeState | 'all'>('pending'), classification = ref<PhoneIntakeClassification | 'all'>('all'), brandId = ref<number>(), page = ref(1)
const data = ref<PhoneIntakePage>(), brands = ref<PhoneBrand[]>([]), loading = ref(false), error = ref(''), notice = ref('')
const writable = computed(() => props.editable && data.value?.canMaintain === true && !!user.token)
const rows = computed(() => data.value?.content || [])
const detail = ref<PhoneIntakeDetail>(), detailOpen = ref(false), detailLoading = ref(false), detailError = ref(''), sourcePage = ref(1), sourceRows = ref<PhoneIntakeSource[]>([]), resolvedPhones = ref<CompatiblePhone[]>([])
const action = ref<PhoneIntakeAction>('link'), modelName = ref(''), editBrandId = ref<number | null>(null), editClassification = ref<PhoneIntakeClassification>('missing'), editReviewReason = ref(''), aliases = ref(''), reason = ref(''), selectedIds = ref<number[]>([]), selectedPhones = ref<CompatiblePhone[]>([])
const resolution = ref<PhoneIntakeResolutionPreview>(), resolutionPage = ref(1), resolutionAck = ref(false), resolutionBusy = ref(false), resolutionId = ref(newRequestId())
const resolutionInitial = ref('')
const collecting = ref(false), fileName = ref(''), sourceInput = ref<PhoneIntakeSourceInput>(), sourcePreview = ref<PhoneIntakeSourcePreview>(), importPage = ref(1), importError = ref(''), importAck = ref(false), importBusy = ref(false), sourceRequestId = ref(newRequestId())
const importRows = computed(() => (sourcePreview.value?.entries || sourceInput.value?.entries || []).map((entry, index) => ({ entry, index })).slice((importPage.value - 1) * 20, importPage.value * 20))
const importTotal = computed(() => sourceInput.value?.entries.length || 0)
const resolutionSources = computed(() => resolution.value?.sources.slice((resolutionPage.value - 1) * 20, resolutionPage.value * 20) || [])
const canResolve = computed(() => writable.value && detail.value?.canMaintain === true)
const pickerMembers = computed(() => [...new Map([...resolvedPhones.value, ...selectedPhones.value].map(phone => [phone.phoneModelId, phone])).values()])
const collectionCounts = computed(() => ({ missing: sourceInput.value?.entries.filter(entry => entry.classification === 'missing').length || 0, review: sourceInput.value?.entries.filter(entry => entry.classification === 'review').length || 0 }))
const busy = computed(() => loading.value || detailLoading.value || resolutionBusy.value || importBusy.value)
const actions: Record<PhoneIntakeAction, string> = { link: '关联已有手机', create: '补建标准成员', defer: '暂不处理', reopen: '重新处理', revise: '核对名称与分类' }
let generation = 0, detailGeneration = 0, importGeneration = 0, resolutionGeneration = 0

async function load() {
  if (!props.visible || !user.token) return
  const serial = ++generation, token = user.token
  loading.value = true; error.value = ''
  try {
    const result = await api.getPhoneIntake({ page: page.value, size: 50, keyword: keyword.value.trim(), state: state.value, classification: classification.value, ...(brandId.value ? { brandId: brandId.value } : {}) })
    if (serial === generation && token === user.token) data.value = result
  } catch (cause) { if (serial === generation && token === user.token) error.value = partnerError(cause) }
  finally { if (serial === generation) loading.value = false }
}
function search() { page.value = 1; void load() }
async function loadBrands() {
  const token = user.token
  try { const result = await getPhoneSelectionBrands(); if (token === user.token) brands.value = result }
  catch (cause) { if (token === user.token) error.value = `品牌读取失败：${partnerError(cause)}` }
}
function invalidateResolution() { resolutionGeneration++; resolution.value = undefined; resolutionAck.value = false; resolutionId.value = newRequestId() }
function resetForm(row: PhoneIntakeDetail) {
  action.value = row.state === 'resolved' || row.state === 'deferred' ? 'reopen' : 'link'
  modelName.value = row.componentText; editBrandId.value = row.brandId; editClassification.value = row.classification; editReviewReason.value = row.reviewReason || ''; aliases.value = ''; reason.value = ''; selectedIds.value = [...row.resolvedPhoneModelIds]; selectedPhones.value = []; invalidateResolution(); resolutionInitial.value = JSON.stringify(resolutionInput.value)
}
async function open(row: PhoneIntakeCandidate) {
  const serial = ++detailGeneration, token = user.token
  detailOpen.value = true; detailLoading.value = true; detailError.value = ''; detail.value = undefined; sourceRows.value = []; resolvedPhones.value = []; sourcePage.value = 1; invalidateResolution()
  try {
    const result = await api.getPhoneIntakeDetail(row.id)
    if (serial !== detailGeneration || token !== user.token || !detailOpen.value) return
    detail.value = result; sourceRows.value = result.sources || []; resetForm(result)
    if (result.resolvedPhoneModelIds.length) {
      const phones = await getPhoneModelOptions(result.resolvedPhoneModelIds)
      if (serial === detailGeneration && token === user.token) resolvedPhones.value = phones
    }
  } catch (cause) { if (serial === detailGeneration && token === user.token) detailError.value = partnerError(cause) }
  finally { if (serial === detailGeneration) detailLoading.value = false }
}
async function loadSources() {
  if (!detail.value || detailLoading.value) return
  const serial = ++detailGeneration, token = user.token, id = detail.value.id
  detailLoading.value = true; detailError.value = ''
  try { const result = await api.getPhoneIntakeSources(id, { page: sourcePage.value, size: 30 }); if (serial === detailGeneration && token === user.token && detail.value?.id === id) sourceRows.value = result.content }
  catch (cause) { if (serial === detailGeneration && token === user.token) detailError.value = partnerError(cause) }
  finally { if (serial === detailGeneration) detailLoading.value = false }
}
const resolutionInput = computed<PhoneIntakeResolutionInput | undefined>(() => {
  const row = detail.value
  if (!row) return
  const input: PhoneIntakeResolutionInput = { version: row.version, original: { state: row.state, classification: row.classification, reviewReason: row.reviewReason, brandId: row.brandId, componentText: row.componentText, resolvedPhoneModelIds: [...row.resolvedPhoneModelIds] }, action: action.value, reason: reason.value.trim() }
  if (action.value === 'link') input.phoneModelIds = [...selectedIds.value]
  if (action.value === 'create') Object.assign(input, { brandId: editBrandId.value, modelName: modelName.value.trim(), aliases: aliases.value.split('\n').map(value => value.trim()).filter(Boolean) })
  if (action.value === 'revise') Object.assign(input, { componentText: modelName.value.trim(), brandId: editBrandId.value, classification: editClassification.value, reviewReason: editClassification.value === 'review' ? editReviewReason.value || null : null })
  return input
})
watch(resolutionInput, invalidateResolution, { deep: true, flush: 'sync' })
async function previewResolution() {
  const input = resolutionInput.value, row = detail.value
  if (!input || !row || !canResolve.value || busy.value) return
  if (!input.reason) { detailError.value = '请填写本次处理依据'; return }
  if (input.action === 'link' && !input.phoneModelIds?.length) { detailError.value = '请先选择已有标准手机成员'; return }
  if (input.action === 'create' && (!input.brandId || !input.modelName)) { detailError.value = '请核准品牌和单一型号名称；不分配手机编码'; return }
  const serial = resolutionGeneration, token = user.token
  resolutionBusy.value = true; detailError.value = ''
  try { const result = await api.previewPhoneIntakeResolution(row.id, input); if (serial === resolutionGeneration && token === user.token && detail.value?.id === row.id) { resolution.value = result; resolutionPage.value = 1; resolutionAck.value = false } }
  catch (cause) { if (serial === resolutionGeneration && token === user.token) detailError.value = partnerError(cause) }
  finally { resolutionBusy.value = false }
}
async function saveResolution() {
  const input = resolutionInput.value, row = detail.value, preview = resolution.value
  if (!input || !row || !preview?.canSave || !preview.previewToken || !resolutionAck.value || !canResolve.value || busy.value) return
  const serial = resolutionGeneration, token = user.token
  resolutionBusy.value = true; detailError.value = ''
  try {
    const result = await api.savePhoneIntakeResolution(row.id, { ...input, requestId: resolutionId.value, previewToken: preview.previewToken })
    if (serial !== resolutionGeneration || token !== user.token) return
    workspace.changed(); emit('changed'); notice.value = `${result.replayed ? '已核实上次处理结果' : '处理已保存'}；${result.updatedSourceCount} 条来源已更新，原文和供方进度保留。`
    detailOpen.value = false; detail.value = undefined; invalidateResolution(); await load()
  } catch (cause) { if (serial === resolutionGeneration && token === user.token) detailError.value = `${partnerError(cause)}；结果未确认，保留本次内容，可按同一请求重试。` }
  finally { resolutionBusy.value = false }
}
function invalidateImport() { importGeneration++; sourcePreview.value = undefined; importAck.value = false; sourceRequestId.value = newRequestId() }
watch(sourceInput, invalidateImport, { deep: true, flush: 'sync' })
function collect() { if (!writable.value || busy.value) return; collecting.value = true; fileName.value = ''; sourceInput.value = undefined; importError.value = ''; importPage.value = 1; invalidateImport() }
async function fileChanged(event: Event) {
  const control = event.target as HTMLInputElement, file = control.files?.[0]
  if (!file || !writable.value || importBusy.value) return
  sourceInput.value = undefined; invalidateImport(); const serial = importGeneration, token = user.token
  fileName.value = file.name; importError.value = ''; importBusy.value = true
  try { const parsed = parsePhoneIntakeSource(await file.text()); if (serial === importGeneration && token === user.token && collecting.value) { sourceInput.value = parsed; importPage.value = 1 } }
  catch (cause) { if (serial === importGeneration && token === user.token) importError.value = partnerError(cause) }
  finally { importBusy.value = false; control.value = '' }
}
function sourceSelected(input: PhoneIntakeSourceInput) { if (!writable.value || busy.value) return; sourceInput.value = input; fileName.value = ''; importError.value = ''; importPage.value = 1 }
function rememberSelected(phones: CompatiblePhone[]) { if (JSON.stringify(phones) !== JSON.stringify(selectedPhones.value)) selectedPhones.value = phones }
function duplicateFragment(index: number) { if (!sourceInput.value || importBusy.value || sourcePreview.value) return; const entry = sourceInput.value.entries[index]; sourceInput.value.entries.splice(index + 1, 0, { ...entry, componentText: '', reason: '' }) }
async function previewSource() {
  if (!sourceInput.value || !writable.value || busy.value) return
  const serial = importGeneration, token = user.token, input = JSON.parse(JSON.stringify(sourceInput.value)) as PhoneIntakeSourceInput
  importBusy.value = true; importError.value = ''
  try {
    const result = await api.previewPhoneIntakeSource(input)
    if (serial !== importGeneration || token !== user.token || !collecting.value) return
    if (result.entries.length !== input.entries.length || result.summary.entryCount !== input.entries.length) throw new Error('预览行数与文件不一致，已停止确认；请核对后重试，不会跳过错误行。')
    sourcePreview.value = result; importPage.value = 1; importAck.value = false
  } catch (cause) { if (serial === importGeneration && token === user.token) importError.value = partnerError(cause) }
  finally { importBusy.value = false }
}
async function saveSource() {
  if (!writable.value || busy.value || !sourceInput.value || !sourcePreview.value?.canSave || !sourcePreview.value.previewToken || !importAck.value) return
  const serial = importGeneration, token = user.token
  importBusy.value = true; importError.value = ''
  try {
    const result = await api.savePhoneIntakeSource({ ...sourceInput.value, previewToken: sourcePreview.value.previewToken, requestId: sourceRequestId.value })
    if (serial !== importGeneration || token !== user.token) return
    collecting.value = false; sourceInput.value = undefined; notice.value = result.replayed ? `已核实上次收集结果：${result.addedSourceCount} 条来源已收集；未重复新增，也未创建手机、产品或样品。` : `待补项已收集，新增 ${result.addedSourceCount} 条来源；未创建手机、产品或样品。`; workspace.changed(); emit('changed'); await load()
  } catch (cause) { if (serial === importGeneration && token === user.token) importError.value = `${partnerError(cause)}；文件和本次预览保留，可按同一请求重试。` }
  finally { importBusy.value = false }
}
async function closeImport(done?: () => void) {
  if (importBusy.value) return
  if (sourceInput.value) { try { await ElMessageBox.confirm(uiText('本次收集尚未确认，关闭不会写入待补项。'), uiText('放弃本次收集？')) } catch { return } }
  collecting.value = false; invalidateImport(); done?.()
}
async function closeDetail() {
  if (resolutionBusy.value) return
  if (canResolve.value && detail.value && JSON.stringify(resolutionInput.value) !== resolutionInitial.value) { try { await ElMessageBox.confirm(uiText('本次处理尚未确认，关闭不会修改手机或供方来源。'), uiText('放弃本次填写？')) } catch { return } }
  detailOpen.value = false; detailGeneration++; invalidateResolution()
}
function phoneLabel(id: number) { const phone = [...resolvedPhones.value, ...selectedPhones.value].find(value => value.phoneModelId === id); return phone ? `${phoneMemberName(phone)}${phone.phoneModelCode ? ` · ${phone.phoneModelCode}` : ' · 编码待分配'}` : '标准手机成员（查看关联资料）' }
function sourceTitle(source: PhoneIntakeSource) { return [source.supplierName, source.sourceName || source.sourceCode, source.styleName, `原序号 ${catalogModelIndexDisplay(source.supplierIndex).label}`, source.latest === true ? '当前来源' : source.latest === false ? '历史来源' : ''].filter(Boolean).join(' · ') }
function stateLabel(value: unknown) { return typeof value === 'string' && value in phoneIntakeStates ? phoneIntakeStates[value as PhoneIntakeState] : '' }
watch(() => props.visible, visible => { if (visible) { void load(); if (!brands.value.length) void loadBrands() } else { generation++; detailGeneration++; importGeneration++; resolutionGeneration++; detailOpen.value = false; collecting.value = false } }, { immediate: true })
watch(() => props.keyword, value => { keyword.value = value || ''; search() })
watch(() => user.token, () => { generation++; detailGeneration++; importGeneration++; resolutionGeneration++; data.value = undefined; detail.value = undefined; brands.value = []; sourceRows.value = []; resolvedPhones.value = []; selectedPhones.value = []; sourceInput.value = undefined; sourcePreview.value = undefined; resolution.value = undefined; detailOpen.value = false; collecting.value = false; notice.value = ''; error.value = '' })
onBeforeUnmount(() => { generation++; detailGeneration++; importGeneration++; resolutionGeneration++ })
</script>

<template>
  <section class="phone-intake" :aria-label='uiText("待补手机型号")'>
    <div class="intake-controls"><el-input v-model="keyword" clearable size="small" :aria-label='uiText("待补型号关键词")' :placeholder='uiText("搜索待补型号或品牌")' @keyup.enter="search"/><el-select v-model="state" size="small" :aria-label='uiText("待补型号状态")' @change="search"><el-option v-for="(label,value) in phoneIntakeStates" :key="value" :label="label" :value="value"/><el-option :label='uiText("全部状态")' value="all"/></el-select><el-select v-model="classification" size="small" :aria-label='uiText("待补型号分类")' @change="search"><el-option :label='uiText("全部问题")' value="all"/><el-option v-for="(label,value) in phoneIntakeClassifications" :key="value" :label="label" :value="value"/></el-select><el-select v-model="brandId" clearable filterable size="small" :aria-label='uiText("待补型号品牌")' :placeholder='uiText("全部品牌")' @change="search"><el-option v-for="brand in brands" :key="brand.brandId" :value="brand.brandId" :label="brand.name"/></el-select><el-button size="small" @click="search">{{ uiText("查询") }}</el-button><el-button size="small" @click="load">{{ uiText("刷新") }}</el-button><el-button v-if="writable" size="small" type="primary" @click="collect">{{ uiText("收集待补项") }}</el-button></div>
    <div class="intake-summary">{{ uiText("待处理 ") }}{{data?.summary.pending||0}}{{ uiText(" · 已解决 ") }}{{data?.summary.resolved||0}}{{ uiText(" · 暂不处理 ") }}{{data?.summary.deferred||0}}<span>{{ uiText("缺档与名称／版本／共模问题分开核对；不自动分配手机编码。") }}</span></div>
    <el-alert v-if="notice" :title="notice" type="success" :closable="false"/><el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load">{{ uiText("重试") }}</el-button></el-alert>
    <FixedTableViewport fill :refresh-key="rows"><div class="intake-list" role="table" :aria-label='uiText("待补手机型号列表")' v-loading="loading"><div class="intake-row intake-heading" role="row"><span>{{ uiText("原型号／品牌") }}</span><span>{{ uiText("问题与依据") }}</span><span>{{ uiText("来源／状态") }}</span><span>{{ uiText("操作") }}</span></div><div class="intake-scroll" role="rowgroup" tabindex="0"><article v-for="row in rows" :key="row.id" class="intake-row intake-record" role="row"><div role="cell"><strong>{{row.componentText}}</strong><small>{{row.brandName||uiText('品牌待核对')}}</small></div><div role="cell"><span>{{phoneIntakeClassifications[row.classification]}}{{row.classification==='review'&&row.reviewReason?` · ${phoneIntakeReviewReasons[row.reviewReason]}`:''}}</span><details v-if="row.reason"><summary>{{ uiText("核对依据") }}</summary><p>{{row.reason}}</p></details></div><div role="cell"><span>{{row.sourceCount}}{{ uiText(" 条来源 · ") }}{{phoneIntakeStates[row.state]}}</span><small v-if="row.resolvedPhoneModelIds.length">{{ uiText("已关联 ") }}{{row.resolvedPhoneModelIds.length}}{{ uiText(" 个标准成员") }}</small></div><div role="cell"><el-button link @click="open(row)">{{writable&&row.canMaintain?uiText('查看与处理'):uiText('查看来源')}}</el-button></div></article><el-empty v-if="!rows.length&&!loading" :description='uiText("当前筛选没有待补手机型号")' :image-size="50"/></div></div></FixedTableViewport>
    <el-pagination v-model:current-page="page" size="small" :page-size="50" :total="data?.totalElements||0" :pager-count="5" layout="total,prev,pager,next" @current-change="load"/>

    <el-drawer :model-value="detailOpen" append-to-body :title='uiText("待补手机型号 · 来源与处理")' size="min(880px,96vw)" :close-on-click-modal="false" :before-close="closeDetail"><div class="intake-detail" v-loading="detailLoading"><el-alert v-if="detailError" :title="detailError" type="error" :closable="false"/><template v-if="detail"><header><h3>{{detail.componentText}}</h3><span>{{detail.brandName||uiText('品牌待核对')}} · {{phoneIntakeClassifications[detail.classification]}} · {{phoneIntakeStates[detail.state]}}</span></header><p>{{detail.reason}}</p><div v-if="resolvedPhones.length" class="intake-linked"><strong>{{ uiText("已关联") }}</strong><span v-for="phone in resolvedPhones" :key="phone.phoneModelId">{{phoneMemberName(phone)}} · {{phone.phoneModelCode||uiText('编码待分配')}}</span></div>
      <section class="intake-sources"><h4>{{ uiText("供方来源（") }}{{detail.sourceCount}}）</h4><article v-for="source in sourceRows" :key="source.id||`${source.recordId}-${source.rowId}-${source.supplierIndex}`"><strong>{{sourceTitle(source)}}</strong><details v-if="catalogModelIndexDisplay(source.supplierIndex).sourceLocation" class="intake-source-location"><summary>{{ uiText("核对说明") }}</summary><p>{{catalogModelIndexDisplay(source.supplierIndex).sourceLocation}}</p></details><p v-if="source.sourceUnavailable" class="intake-error">{{ uiText("当前来源暂不可核对，办理前请先处理访问或版本问题。") }}</p><p>{{source.rawBrandGroup}} {{source.rawModelText}}</p><p><span v-if="source.rawStatus">{{ uiText("供方原进度：") }}{{source.rawStatus}}</span><span v-if="source.rawNote"> · {{source.rawNote}}</span></p><PartnerFiles v-if="!source.sourceUnavailable&&source.modelSheetFileDetails?.length" kind="partner-selections" :id="source.recordId" :files="source.modelSheetFileDetails" :model-value="source.modelSheetRefs||[]" :can-write="false" :can-write-money="false" compact/></article><el-pagination v-if="detail.sourceCount>30" v-model:current-page="sourcePage" :page-size="30" :total="detail.sourceCount" :pager-count="5" layout="total,prev,pager,next" @current-change="loadSources"/></section>
      <section v-if="canResolve" class="intake-process"><h4>{{ uiText("本次处理") }}</h4><el-form label-position="top" size="small" :disabled="resolutionBusy"><el-form-item :label='uiText("处理方式")'><el-select v-model="action" :aria-label='uiText("待补型号处理方式")'><el-option v-if="detail.state!=='pending'" :label='uiText("重新处理")' value="reopen"/><template v-if="detail.state!=='resolved'"><el-option :label='uiText("关联已有手机")' value="link"/><el-option v-if="detail.classification==='missing'" :label='uiText("补建标准成员（编码留空）")' value="create"/><el-option :label='uiText("核对名称与分类")' value="revise"/><el-option :label='uiText("暂不处理")' value="defer"/></template></el-select></el-form-item>
        <el-form-item v-if="action==='link'" :label='uiText("关联已有标准手机成员")'><PhoneModelMultiSelect v-model="selectedIds" :members="pickerMembers" :aria-label='uiText("待补项关联已有手机")' @selected="rememberSelected"/></el-form-item>
        <template v-if="action==='create'||action==='revise'"><div class="intake-form-grid"><el-form-item :label='uiText("已核准品牌")'><el-select v-model="editBrandId" filterable clearable :aria-label='uiText("待补项核准品牌")'><el-option v-for="brand in brands" :key="brand.brandId" :label="brand.name" :value="brand.brandId" :disabled="brand.status!=='enabled'"/></el-select></el-form-item><el-form-item :label='uiText("标准型号名称")'><el-input v-model="modelName" maxlength="255" :aria-label='uiText("待补项标准型号名称")'/></el-form-item></div><p v-if="action==='create'" class="intake-note">{{ uiText("只补建已核准的单一标准手机成员，编码留空；来源原文、供方进度和历史快照不改写。") }}</p><el-form-item v-if="action==='create'" :label='uiText("型号别名（选填，每行一个）")'><el-input v-model="aliases" type="textarea" :rows="2"/></el-form-item><div v-else class="intake-form-grid"><el-form-item :label='uiText("核对后的问题分类")'><el-select v-model="editClassification" :aria-label='uiText("核准问题分类")'><el-option v-for="(label,value) in phoneIntakeClassifications" :key="value" :label="label" :value="value"/></el-select></el-form-item><el-form-item v-if="editClassification==='review'" :label='uiText("具体问题")'><el-select v-model="editReviewReason" :aria-label='uiText("核准问题类型")'><el-option v-for="(label,value) in phoneIntakeReviewReasons" :key="value" :label="label" :value="value"/></el-select></el-form-item></div></template>
        <el-form-item :label='uiText("本次处理依据")'><el-input v-model="reason" type="textarea" :rows="2" maxlength="1000" :aria-label='uiText("待补项处理依据")' :placeholder='uiText("说明关联、补建、核对或暂不处理的依据")'/></el-form-item><el-button :loading="resolutionBusy" @click="previewResolution">{{ uiText("预览处理结果") }}</el-button></el-form>
        <section v-if="resolution" class="intake-preview" :aria-label='uiText("待补项处理预览")'><strong>{{actions[resolution.action]}} → {{phoneIntakeStates[resolution.after.state]}}</strong><p v-if="resolution.proposedPhone">{{ uiText("拟补建 ") }}{{brands.find(brand=>brand.brandId===resolution?.proposedPhone?.brandId)?.name}} {{resolution.proposedPhone.modelName}}{{ uiText(" · 手机编码留空") }}</p><p v-if="resolution.action==='revise'">{{detail.componentText}} → {{resolution.after.componentText||detail.componentText}} · {{phoneIntakeClassifications[resolution.after.classification||detail.classification]}}</p><p>{{ uiText("核对 ") }}{{resolution.summary.sourceCount}}{{ uiText(" 条来源，本次更新 ") }}{{resolution.summary.changedSourceCount}}{{ uiText(" 条；保留原型号、原进度和原备注。") }}</p><p v-if="action==='link'">{{ uiText("关联：") }}{{selectedIds.map(phoneLabel).join('；')}}</p><el-alert v-for="line in resolution.blockers" :key="line" :title="line" type="error" :closable="false"/><p v-for="line in resolution.messages" :key="line">{{line}}</p><details v-if="resolution.sources.length"><summary>{{ uiText("查看本次来源变更") }}</summary><article v-for="source in resolutionSources" :key="source.id"><strong>{{sourceTitle(source)}}</strong><details v-if="catalogModelIndexDisplay(source.supplierIndex).sourceLocation" class="intake-source-location"><summary>{{ uiText("核对说明") }}</summary><p>{{catalogModelIndexDisplay(source.supplierIndex).sourceLocation}}</p></details><p>{{source.rawModelText}} · {{source.rawStatus}} {{source.rawNote}}</p><p>{{source.willUpdate?uiText('将补充关联'):uiText('原关联保留')}}{{source.requiresNewPhone?uiText(' · 使用本次新建成员'):uiText(' · 新增 {value0} 个／已有 {value1} 个',{value0:(source.addedPhoneModelIds?.length||0),value1:(source.alreadyLinkedPhoneModelIds?.length||0)})}}</p></article><el-pagination v-if="resolution.sources.length>20" v-model:current-page="resolutionPage" :page-size="20" :total="resolution.sources.length" layout="prev,pager,next"/></details><el-checkbox v-if="resolution.canSave" v-model="resolutionAck" :disabled="resolutionBusy">{{ uiText("已核对本次处理及所有来源") }}</el-checkbox><el-button type="primary" :disabled="!resolution.canSave||!resolutionAck||busy" :loading="resolutionBusy" @click="saveResolution">{{ uiText("确认处理") }}</el-button></section>
      </section><p v-else class="intake-note">{{ uiText("当前账号只读，原来源和已有关联保留。") }}</p>
    </template></div></el-drawer>

    <el-dialog :model-value="collecting" append-to-body :title='uiText("收集待补手机型号")' width="min(1080px,96vw)" top="4vh" :close-on-click-modal="false" :before-close="closeImport" class="phone-intake-import"><section class="intake-import"><PhoneIntakeSourcePicker v-if="!sourceInput" :disabled="!writable||importBusy" @selected="sourceSelected"/><details class="intake-json"><summary>{{ uiText("批量整理文件（选填）") }}</summary><div class="intake-file"><label>{{ uiText("选择单款待补项 JSON ") }}<input type="file" accept=".json,application/json" :disabled="!writable||importBusy" :aria-label='uiText("待补项JSON文件")' @change="fileChanged"/></label><span v-if="fileName">{{fileName}}</span></div></details><p class="intake-note">{{ uiText("只收集待核对的型号片段；原型号、供方进度及图片从系统来源读取，不创建手机成员。") }}</p><el-alert v-if="importError" :title="importError" type="error" :closable="false"/><template v-if="sourceInput"><el-button v-if="!sourcePreview" size="small" link :disabled="importBusy" @click="sourceInput=undefined">{{ uiText("重新选择来源款式") }}</el-button><p>{{importTotal}}{{ uiText(" 条待补项 · 确实缺档 ") }}{{collectionCounts.missing}}{{ uiText(" · 需要核对 ") }}{{collectionCounts.review}} · {{sourcePreview?uiText('新增 {value0} · 追加来源 {value1} · 已收集 {value2}',{value0:(sourcePreview.summary.newCount),value1:(sourcePreview.summary.appendCount),value2:(sourcePreview.summary.reusedCount)}):uiText('先核对分类、品牌和型号片段，再预览')}}</p><div class="intake-import-scroll"><article v-for="{entry,index} in importRows" :key="index" class="intake-import-row"><div class="intake-import-index"><strong>{{ uiText("原序号 ") }}{{catalogModelIndexDisplay(entry.supplierIndex).label}}</strong><details v-if="catalogModelIndexDisplay(entry.supplierIndex).sourceLocation"><summary>{{ uiText("核对说明") }}</summary><p>{{catalogModelIndexDisplay(entry.supplierIndex).sourceLocation}}</p></details></div><template v-if="!sourcePreview"><el-input v-model="sourceInput.entries[index].componentText" :disabled="importBusy" :aria-label='uiText("收集型号片段")'/><el-select v-model="sourceInput.entries[index].brandId" clearable filterable :disabled="importBusy" :aria-label='uiText("收集型号品牌")' :placeholder='uiText("品牌待核对")'><el-option v-for="brand in brands" :key="brand.brandId" :label="brand.name" :value="brand.brandId" :disabled="brand.status!=='enabled'"/></el-select><el-select v-model="sourceInput.entries[index].classification" :disabled="importBusy" :aria-label='uiText("收集问题分类")'><el-option v-for="(label,value) in phoneIntakeClassifications" :key="value" :label="label" :value="value"/></el-select><el-select v-if="entry.classification==='review'" v-model="sourceInput.entries[index].reviewReason" :disabled="importBusy" :aria-label='uiText("收集核对类型")' :placeholder='uiText("选择具体问题")'><el-option v-for="(label,value) in phoneIntakeReviewReasons" :key="value" :label="label" :value="value"/></el-select><el-input v-model="sourceInput.entries[index].reason" :disabled="importBusy" :aria-label='uiText("收集核对依据")' :placeholder='uiText("核对依据")'/><el-button size="small" link :disabled="importBusy" @click="duplicateFragment(index)">{{ uiText("再补一个片段") }}</el-button></template><template v-else><div><strong>{{entry.componentText}}</strong><span>{{brands.find(brand=>brand.brandId===entry.brandId)?.name||uiText('品牌待核对')}} · {{phoneIntakeClassifications[entry.classification]}}{{entry.reviewReason?` · ${phoneIntakeReviewReasons[entry.reviewReason]}`:''}}</span><p v-if="'source' in entry">{{(entry.source as PhoneIntakeSource).rawModelText}} · {{(entry.source as PhoneIntakeSource).rawStatus}} {{(entry.source as PhoneIntakeSource).rawNote}}</p><p>{{entry.reason}}</p><p v-if="'action' in entry">{{entry.action==='new'?uiText('新增候选'):entry.action==='append'?uiText('追加来源'):entry.action==='unchanged'?uiText('原来源已收集'):uiText('待授权核对')}}<span v-if="'existingState' in entry&&entry.existingState"> · {{uiText(String((stateLabel(entry.existingState)) ?? ''))}}</span></p><p v-for="line in ('messages' in entry?entry.messages:[])" :key="String(line)" class="intake-error">{{line}}</p></div></template></article></div><el-pagination v-model:current-page="importPage" :page-size="20" :total="importTotal" :pager-count="5" layout="total,prev,pager,next"/><p v-for="line in sourcePreview?.messages" :key="line" class="intake-error">{{line}}</p><el-checkbox v-if="sourcePreview?.canSave" v-model="importAck" :disabled="importBusy">{{ uiText("已核对全部条目和来源，仅收集待补项") }}</el-checkbox></template></section><template #footer><el-button :disabled="importBusy" @click="closeImport()">{{ uiText("取消") }}</el-button><el-button v-if="sourcePreview" :disabled="importBusy" @click="sourcePreview=undefined;importAck=false">{{ uiText("返回修改") }}</el-button><el-button v-if="!sourcePreview" type="primary" :disabled="!sourceInput||!writable||busy" :loading="importBusy" @click="previewSource">{{ uiText("预览收集结果") }}</el-button><el-button v-else type="primary" :disabled="!sourcePreview.canSave||!importAck||!writable||busy" :loading="importBusy" @click="saveSource">{{ uiText("确认收集待补项") }}</el-button></template></el-dialog>
  </section>
</template>

<style scoped>
.phone-intake{display:flex;flex:1;flex-direction:column;min-width:0;min-height:0;gap:5px;container-type:inline-size;font-size:12px}.intake-controls{display:flex;gap:5px;flex-wrap:wrap;flex:none}.intake-controls>.el-input{flex:1;min-width:160px}.intake-controls>.el-select{width:130px}.intake-controls>.el-button+.el-button{margin-left:0}.intake-summary{display:flex;gap:12px;flex-wrap:wrap;color:#60728a;line-height:18px}.intake-summary>span{margin-left:auto}.intake-list{height:100%;display:flex;flex-direction:column;overflow:hidden;border:1px solid #e1e8f1;border-radius:4px}.intake-scroll{min-height:0;flex:1;overflow:auto;overflow-x:hidden}.intake-row{display:grid;grid-template-columns:minmax(0,1.4fr) minmax(0,1.4fr) minmax(0,1fr) 96px;align-items:start;gap:8px;padding:6px 8px;line-height:19px;overflow-wrap:anywhere}.intake-row>div{min-width:0}.intake-heading{background:#f1f5fa;flex:none;color:#536b88}.intake-record{border-bottom:1px solid #e5ebf3;min-height:46px;box-sizing:border-box}.intake-record:nth-child(even){background:#fafbfe}.intake-record strong{font-size:13px}.intake-record small{display:block;font-size:12px;color:#62768e}.intake-row details{display:inline-block;vertical-align:top;margin-left:5px}.intake-row details[open]{display:block;margin-left:0}summary{color:#2d69ad;cursor:pointer}p{margin:4px 0;line-height:1.6;white-space:pre-wrap;overflow-wrap:anywhere}.el-pagination{flex:none;justify-content:flex-end}.intake-detail{font-size:13px;min-width:0}.intake-detail h3,.intake-detail h4{margin:0 0 7px;font-size:15px}.intake-detail header>span,.intake-note{color:#64768c}.intake-sources,.intake-process{margin-top:12px;border-top:1px solid #e3eaf3;padding-top:10px}.intake-sources article,.intake-preview article{border-bottom:1px solid #e5ebf3;padding:7px 0;overflow-wrap:anywhere}.intake-linked{display:flex;gap:7px;flex-wrap:wrap;margin-top:8px}.intake-form-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:10px}.intake-process .el-select{width:100%}.intake-preview{margin-top:10px;padding:9px;background:#f6f9fc;border:1px solid #e2eaf4;border-radius:4px}.intake-preview>.el-button{display:block;margin:7px 0 0}.intake-preview .el-checkbox,.intake-import>.el-checkbox{height:auto;white-space:normal;margin-top:8px}.intake-preview :deep(.el-checkbox__label),.intake-import :deep(.el-checkbox__label){white-space:normal;line-height:20px}.intake-file{display:flex;align-items:center;gap:8px;flex-wrap:wrap;overflow-wrap:anywhere}.intake-file input{max-width:100%;display:block;margin-top:5px}.intake-import{display:flex;flex-direction:column;min-height:0;max-height:72dvh;font-size:13px}.intake-import-scroll{min-height:80px;overflow:auto;flex:1;container-type:inline-size}.intake-import-row{display:flex;gap:6px;align-items:center;flex-wrap:wrap;padding:7px 2px;border-bottom:1px solid #e4ebf3}.intake-import-row>.intake-import-index{width:82px;flex:none;overflow-wrap:anywhere}.intake-import-row>.el-input{flex:1;min-width:145px}.intake-import-row>.el-select{width:145px}.intake-import-row>div:not(.el-input):not(.el-select):not(.intake-import-index){min-width:0;flex:1}.intake-import-row div>span{display:block}.intake-error{color:#a34625}.intake-note{font-size:12px}.phone-intake :deep(.el-button),.phone-intake :deep(.el-select),.phone-intake :deep(.el-input){font-size:12px}.phone-intake :deep(.el-button:not(.is-link)){height:28px}.intake-record .el-button{height:auto;min-height:24px;padding:0;white-space:normal;line-height:19px}.intake-detail :deep(.partner-files--compact header h3){font-size:13px}
@container(max-width:760px){.intake-summary>span{margin-left:0}.intake-controls>.el-select{flex:1;min-width:125px}.intake-controls>.el-input{flex-basis:100%}.intake-row{padding-block:4px;grid-template-columns:minmax(0,1.3fr) minmax(0,1.2fr) 106px;gap:4px 7px}.intake-row>div:nth-child(3){grid-column:1/3;grid-row:2}.intake-row>div:last-child{grid-column:3;grid-row:1/3}.intake-heading>span:nth-child(3){display:none}.intake-heading>span:last-child{grid-column:3}.intake-record small{display:inline;margin-left:6px}}
@media(max-width:540px){.intake-form-grid{grid-template-columns:minmax(0,1fr)}}
</style>
