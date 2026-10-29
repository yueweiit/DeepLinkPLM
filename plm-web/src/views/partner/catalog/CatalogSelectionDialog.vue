<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PhoneModelMultiSelect from '@/components/PhoneModelMultiSelect/index.vue'
import { unavailablePhone } from '@/components/PhoneModelMultiSelect/selection'
import { addCatalogSelection, previewCatalogSelection } from '@/api/modules/partnerCatalog'
import { newRequestId } from '@/api/modules/partner'
import type { CatalogAddition, CatalogAdditionPreview, CatalogAdditionResult, CatalogSelectionItem, CatalogStyle } from '@/types/partnerCatalog'
import type { CompatiblePhone } from '@/types/molds'
import { partnerError } from '@/utils/partnerError'
import PartnerPhoto from '../PartnerPhoto.vue'
import { catalogAllowedPhoneIds, catalogIdentity, catalogKey, catalogPhoneFamilies } from './catalogPresentation'
import { catalogPhoneProgress } from './catalogModelSheet'

const props = defineProps<{ modelValue: boolean; supplierCode: string; supplierName: string; items: CatalogStyle[]; canWrite: boolean; targetSelectionId?:number; targetVersion?:number }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; added: [result: CatalogAdditionResult]; busy:[value:boolean] }>()
const busy=ref(false),error=ref(''),requestId=ref(''),unknown=ref(false)
let pendingCommand:CatalogAddition|undefined
const chosen = ref<Record<string, number[]>>({}), pairs = ref<Record<string, string[]>>({}), preview = ref<CatalogAdditionPreview>()
const sampleQuantities=ref<Record<string,number|undefined>>({}),quantityAdjusted=ref<Record<string,boolean>>({}),commonSampleQty=ref<number>()
const selectedPhones = ref<Record<string, CompatiblePhone[]>>({})
const progressPages = ref<Record<string,number>>({})
const closing = ref(false)
const commonModels = ref<number[]>([]), commonPhones = ref<CompatiblePhone[]>([])
const adjusted = ref<Record<string, boolean>>({}), excluded = ref<Record<string, boolean>>({}), expanded = ref<Record<string, boolean>>({})
const unmatched = ref<Record<string, number[]>>({}), batchMode = ref<'common' | 'all'>('common'), batchMessage = ref('')
let generation = 0
const pairKey = (pair: { phoneModelId: number; colorId: number }) => `${pair.phoneModelId}:${pair.colorId}`
const allowedModels = catalogAllowedPhoneIds
const unspecifiedScope = (row?: Pick<CatalogStyle, 'phoneScopeMode'>) => row?.phoneScopeMode === 'unspecified'
const selectorScope = (row: CatalogStyle) => unspecifiedScope(row) ? undefined : allowedModels(row)
const modelFamilies = computed(() => Object.fromEntries(props.items.map(row => [catalogKey(row), unspecifiedScope(row) ? [] : catalogPhoneFamilies(row)])))
const knownPairs = (row: CatalogStyle) => (row.pairings || []).filter(pair => pair.state !== 'unavailable' && pair.colorSnapshot?.name && chosen.value[catalogKey(row)]?.includes(pair.phoneModelId))
const phoneRecord = (row: CatalogStyle, id: number) => selectedPhones.value[catalogKey(row)]?.find(phone => phone.phoneModelId === id) || row.phones.find(phone => phone.phoneModelId === id) || commonPhones.value.find(phone => phone.phoneModelId === id)
const phoneName = (row: CatalogStyle, id: number, preferred: CompatiblePhone[] = []) => { const member = preferred.find(phone => phone.phoneModelId === id) || phoneRecord(row,id); return member?.modelName || member?.displayName || '型号资料待加载' }
const phoneList = (row: CatalogStyle | undefined, ids: number[], preferred: CompatiblePhone[] = []) => ids.map(id => row ? phoneName(row, id, preferred) : preferred.find(phone => phone.phoneModelId === id)?.modelName || '型号资料待加载').join('、') || '无'
function modelAllowed(row: CatalogStyle, id: number) {
  const member = phoneRecord(row,id)
  return row.phoneScopeMode !== 'unresolved' && Number.isSafeInteger(id) && id > 0 && !row.unavailablePhoneModelIds?.includes(id) && (!member || !unavailablePhone(member)) && (unspecifiedScope(row) ? !!member : allowedModels(row).includes(id))
}
function rememberPhones(row: CatalogStyle, members: CompatiblePhone[]) {
  if (!props.modelValue || busy.value || unknown.value || !props.canWrite) return
  selectedPhones.value[catalogKey(row)] = members.filter(phone => chosen.value[catalogKey(row)]?.includes(phone.phoneModelId))
}
const sourceRow = (item: CatalogSelectionItem) => props.items.find(row => catalogKey(row) === catalogKey(item))
const progressPhones = (row:CatalogStyle) => (chosen.value[catalogKey(row)]||[]).filter(id=>catalogPhoneProgress(row,id).length)
const progressPage = (row:CatalogStyle) => Math.min(progressPages.value[catalogKey(row)]||1,Math.max(1,Math.ceil(progressPhones(row).length/6)))
const visibleProgress = (row:CatalogStyle) => progressPhones(row).slice((progressPage(row)-1)*6,progressPage(row)*6)
const photo = (row: CatalogStyle) => row.fileDetails?.find(file => file.type.startsWith('image/'))
const batchPhones = computed(() => [...new Map([...props.items.flatMap(row => [...row.phones, ...selectedPhones.value[catalogKey(row)] || []]), ...commonPhones.value].map(phone => [phone.phoneModelId, phone])).values()])
const batchScope = computed(() => props.items.some(unspecifiedScope) ? undefined : [...new Set(props.items.flatMap(allowedModels))].filter(id => {
  const member = batchPhones.value.find(phone => phone.phoneModelId === id)
  return !member || !unavailablePhone(member)
}))
const batchFamilies = computed(() => catalogPhoneFamilies({ phones: batchPhones.value, phoneModelIds: [...new Set(props.items.filter(row => !unspecifiedScope(row)).flatMap(allowedModels))], unavailablePhoneModelIds: [] }))
const selectedItems = computed<CatalogSelectionItem[]>(() => props.items.filter(row => !excluded.value[catalogKey(row)]).map(row => {
  const key = catalogKey(row), phoneModelIds = chosen.value[key] || []
  const selectedPairs = knownPairs(row).filter(pair => pairs.value[key]?.includes(pairKey(pair))).map(({ phoneModelId, colorId }) => ({ phoneModelId, colorId }))
  return { ...catalogIdentity(row), phoneModelIds, ...(sampleQuantities.value[key]!==undefined?{sampleQty:sampleQuantities.value[key]}:{}), ...(selectedPairs.length ? { selectedPairs } : {}) }
}))
const selectedStyleCount = computed(() => selectedItems.value.filter(item => item.phoneModelIds.length > 0).length)
const combinationCount = computed(() => selectedItems.value.reduce((total, item) => total + new Set(item.phoneModelIds).size, 0))
const pendingRows = computed(() => props.items.filter(row => !excluded.value[catalogKey(row)] && !(chosen.value[catalogKey(row)] || []).length))
const modelsValid = computed(() => props.canWrite && selectedItems.value.length > 0 && selectedItems.value.every(item => item.phoneModelIds.length > 0 && item.phoneModelIds.every(id => modelAllowed(sourceRow(item)!,id))))
const quantitiesValid=computed(()=>selectedItems.value.every(item=>item.sampleQty===undefined||Number.isSafeInteger(item.sampleQty)&&item.sampleQty>=0))
const plannedTotal=computed(()=>selectedItems.value.reduce((sum,item)=>sum+(item.sampleQty||0),0)),unplannedCount=computed(()=>selectedItems.value.filter(item=>item.sampleQty===undefined).length)
const validationIssues=computed(()=>preview.value&&!preview.value.canAdd?[...(preview.value.messages||[]),...preview.value.items.flatMap(item=>(item.messages||[]).map(message=>`${item.name}：${message}`))]:[])
const valid=computed(()=>modelsValid.value&&quantitiesValid.value)
const hasUnsavedChoice = computed(() => Object.values(sampleQuantities.value).some(value=>value!==undefined)||commonModels.value.length > 0 || Object.values(adjusted.value).some(Boolean) || Object.values(chosen.value).some(ids => ids.length > 0) || Object.values(pairs.value).some(values => values.length > 0))
function payload(): Omit<CatalogAddition, 'requestId' | 'previewToken'> {return {supplierCode:props.supplierCode,targetMode:'supplier_directory',items:selectedItems.value,...(props.targetSelectionId!==undefined?{targetSelectionId:props.targetSelectionId}:{}),...(props.targetVersion!==undefined?{targetVersion:props.targetVersion}:{})}}
watch(() => props.modelValue, open => {
  generation++;if(unknown.value&&pendingCommand)return;error.value='';preview.value=undefined;pendingCommand=undefined
  if (!open) return
  chosen.value = Object.fromEntries(props.items.map(row => [catalogKey(row), []]))
  selectedPhones.value = Object.fromEntries(props.items.map(row => [catalogKey(row), []]))
  progressPages.value = {}
  commonModels.value = []; commonPhones.value = []; adjusted.value = {}; excluded.value = {}; expanded.value = {}; unmatched.value = {}; batchMode.value = 'common'; batchMessage.value = ''
  sampleQuantities.value={};quantityAdjusted.value={};commonSampleQty.value=undefined
  pairs.value = Object.fromEntries(props.items.map(row => [catalogKey(row), []])); requestId.value = newRequestId();
})
watch([chosen,pairs,excluded,sampleQuantities],()=>{if(unknown.value)return;preview.value=undefined;pendingCommand=undefined;requestId.value=newRequestId();error.value=''},{deep:true})
watch(()=>[props.supplierCode,props.items,props.targetSelectionId,props.targetVersion],()=>{generation++;if(!unknown.value){preview.value=undefined;pendingCommand=undefined;requestId.value=newRequestId()}},{deep:true})
watch([busy,unknown],()=>emit('busy',busy.value||unknown.value),{flush:'sync'})
onBeforeUnmount(()=>{generation++;emit('busy',false)})
async function close(done?: () => void) {
  if(busy.value||closing.value)return
  if(unknown.value){ElMessage.warning('请先核对本次保存结果，避免重复提交。');return}
  const serial = generation
  closing.value = true
  try {
    if (hasUnsavedChoice.value) await ElMessageBox.confirm('本次型号或拟拿样数量还未保存，确定放弃并关闭吗？', '保留本次选品', { confirmButtonText: '放弃并关闭', cancelButtonText: '继续选品', closeOnClickModal: false })
    if (serial !== generation || !props.modelValue) return
    done?.(); emit('update:modelValue', false)
  } catch { /* Keep every temporary choice when the user continues or dismisses the prompt. */ }
  finally { closing.value = false }
}
function writeModels(row: CatalogStyle, ids: number[]) {
  const key = catalogKey(row), unique = [...new Set(ids)]
  chosen.value[key] = unique
  pairs.value[key] = (pairs.value[key] || []).filter(value => unique.some(id => value.startsWith(`${id}:`)))
}
function changeModels(row: CatalogStyle, ids: number[]) {
  if (busy.value || unknown.value || !props.canWrite) return
  const key = catalogKey(row)
  writeModels(row, ids); adjusted.value[key] = true; excluded.value[key] = ids.length === 0; unmatched.value[key] = []
}
function changePairs(row: CatalogStyle, values: string[]) {
  if (busy.value || unknown.value || !props.canWrite) return
  const key = catalogKey(row)
  pairs.value[key] = [...values]; adjusted.value[key] = true
}
function applyRow(row: CatalogStyle) {
  const key = catalogKey(row), requested = batchMode.value === 'all' ? (unspecifiedScope(row) ? [] : allowedModels(row)) : commonModels.value
  const allowed = requested.filter(id => modelAllowed(row,id))
  unmatched.value[key] = requested.filter(id => !allowed.includes(id))
  writeModels(row, allowed)
  selectedPhones.value[key] = allowed.map(id => batchPhones.value.find(phone => phone.phoneModelId === id)).filter((phone): phone is CompatiblePhone => !!phone)
  excluded.value[key] = false
}
function applyBatch(mode: 'common' | 'all' = 'common') {
  if (busy.value || unknown.value || !props.canWrite || mode === 'common' && !commonModels.value.length) return
  batchMode.value = mode
  let kept = 0
  for (const row of props.items) {
    if (adjusted.value[catalogKey(row)]) { kept++; continue }
    applyRow(row)
  }
  batchMessage.value = `已更新本次选样范围${kept ? `；保留 ${kept} 款单独调整` : ''}。`
}
function restoreBatch(row: CatalogStyle) {
  if (busy.value || unknown.value || !props.canWrite) return
  adjusted.value[catalogKey(row)] = false; applyRow(row)
}
function chooseBatchFamily(ids: number[]) {
  commonPhones.value = ids.map(id => batchPhones.value.find(phone => phone.phoneModelId === id)).filter((phone): phone is CompatiblePhone => !!phone)
  commonModels.value = [...ids]; applyBatch()
}
function clearBatch() {
  if (busy.value || unknown.value || !props.canWrite) return
  commonModels.value = []; commonPhones.value = []; batchMode.value = 'common'; adjusted.value = {}; excluded.value = {}; unmatched.value = {}
  for (const row of props.items) writeModels(row, [])
  batchMessage.value = '已清空本次型号选择。'
}
function familyChosen(row: CatalogStyle, ids: number[]) { return ids.every(id => chosen.value[catalogKey(row)]?.includes(id)) }
function toggleFamily(row: CatalogStyle, ids: number[]) {
  if (busy.value || unknown.value || !props.canWrite) return
  const allowed = new Set(allowedModels(row)), members = ids.filter(id => allowed.has(id)), current = chosen.value[catalogKey(row)] || []
  changeModels(row, familyChosen(row, members) ? current.filter(id => !members.includes(id)) : [...new Set([...current, ...members])])
}
function changeQuantity(row:CatalogStyle,value:number|undefined){sampleQuantities.value[catalogKey(row)]=value??undefined;quantityAdjusted.value[catalogKey(row)]=true}
function applyQuantity(){if(busy.value||unknown.value||!props.canWrite)return;for(const row of props.items)if(!quantityAdjusted.value[catalogKey(row)])sampleQuantities.value[catalogKey(row)]=commonSampleQty.value??undefined}
function followQuantity(row:CatalogStyle){quantityAdjusted.value[catalogKey(row)]=false;sampleQuantities.value[catalogKey(row)]=commonSampleQty.value??undefined}
function resultUnknown(cause:unknown){const failure=cause as {code?:string;response?:{status?:number}};return ['ECONNABORTED','ETIMEDOUT','ERR_NETWORK'].includes(failure?.code||'')||Number(failure?.response?.status)>=500}
async function submit(){
 if(!props.canWrite||busy.value||(!unknown.value&&!valid.value))return
 if(pendingCommand&&(pendingCommand.supplierCode!==props.supplierCode||pendingCommand.targetSelectionId!==props.targetSelectionId||pendingCommand.targetVersion!==props.targetVersion)){error.value='待核对的保存属于原供应商或原选品目录，请返回原页面核对。';return}
 const serial=generation,input=payload(),fingerprint=JSON.stringify(input)
 busy.value=true;error.value=''
 try{
  if(!pendingCommand){if(!preview.value?.canAdd||!preview.value.previewToken){const checked=await previewCatalogSelection(input);if(serial!==generation||!props.modelValue||fingerprint!==JSON.stringify(payload()))return;preview.value=checked;if(!checked.canAdd||!checked.previewToken)return}pendingCommand=JSON.parse(JSON.stringify({...input,requestId:requestId.value,previewToken:preview.value.previewToken})) as CatalogAddition}
  const result=await addCatalogSelection(pendingCommand)
  if(serial!==generation||!props.modelValue)return
  unknown.value=false;pendingCommand=undefined;emit('added',result);emit('update:modelValue',false)
  ElMessage.success(`已保存 ${selectedStyleCount.value} 款选样${plannedTotal.value?`，拟拿样合计 ${plannedTotal.value} 件`:''}；实际收样另行登记`)
 }catch(cause){if(serial===generation&&props.modelValue){unknown.value=!!pendingCommand&&resultUnknown(cause);if(unknown.value)error.value='保存结果待核对，请点击“核对保存结果”；会沿用本次资料，不重复选样。';else{pendingCommand=undefined;preview.value=undefined;error.value=partnerError(cause)}}}finally{busy.value=false}
}

</script>

<template>
  <el-dialog :model-value="modelValue" class="catalog-selection-dialog-shell" title="选择样品" width="min(880px,96vw)" top="20px" append-to-body :close-on-click-modal="false" :close-on-press-escape="!busy&&!unknown" :show-close="!busy&&!unknown" :before-close="close">
    <div class="catalog-selection-dialog">
      <p class="selection-intro">{{ supplierName || supplierCode }} · 已勾选 {{ items.length }} 款</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
      <section class="selection-models">
        <section class="selection-batch" aria-label="统一批量选择">
          <div class="selection-section-heading"><h3>统一选择手机型号</h3><span>应用到未单独调整的款式</span></div>
          <div class="selection-batch-controls">
            <PhoneModelMultiSelect v-if="batchScope===undefined||batchScope.length" v-model="commonModels" class="selection-batch-picker" :members="batchPhones" :allowed-ids="batchScope" :disabled="busy||unknown||!canWrite" aria-label="批量选择系列／型号" placeholder="选择手机系列／型号" @selected="commonPhones=$event"/>
            <span v-else class="selection-scope-note">本批暂无可批量选择的型号，请逐款核对。</span>
            <el-button type="primary" :disabled="busy||unknown||!canWrite||!commonModels.length" @click="applyBatch()">应用到 {{items.length}} 款</el-button>
          </div>
          <div class="selection-batch-shortcuts"><span>快捷选择</span><el-button link :disabled="busy||unknown||!canWrite" @click="applyBatch('all')">各款可选型号全选</el-button><el-button v-for="family in batchFamilies" :key="family.key" link :disabled="busy||unknown||!canWrite" @click="chooseBatchFamily(family.phoneModelIds)">选择{{family.name}}（{{family.phoneModelIds.length}}）</el-button><el-button link class="selection-clear" :disabled="busy||unknown||!canWrite" @click="clearBatch">清空</el-button></div>
          <div class="selection-quantity-batch"><label>拟拿样数量（每款合计）</label><el-input-number v-model="commonSampleQty" :key="'common-qty:'+(busy||unknown||!canWrite)" :min="0" :max="Number.MAX_SAFE_INTEGER" :precision="0" :controls="false" placeholder="如 2" aria-label="每款统一拟拿样数量" :disabled="busy||unknown||!canWrite"/><el-button :disabled="busy||unknown||!canWrite" @click="applyQuantity">应用每款数量</el-button><small>保留单款例外，不按型号数相乘</small></div>
        </section>
        <div class="selection-section-heading selection-check-heading"><h3>逐款核对</h3><span>{{selectedStyleCount}} 款已选<template v-if="pendingRows.length"> · {{pendingRows.length}} 款待选择</template></span></div>
        <div class="selection-table-heading"><span>款式</span><span>本次选中型号</span><span>拟拿样数量</span><span>操作</span></div>
        <article v-for="row in items" :key="catalogKey(row)" class="selection-model-row" :class="{'is-excluded':excluded[catalogKey(row)]}">
          <div class="selection-row-main">
            <header><PartnerPhoto v-if="photo(row)" :id="photo(row)!.id"/><span v-else class="selection-photo-placeholder" aria-label="暂无款式图片">暂无图片</span><div><strong>{{row.name}}</strong><small><template v-if="unspecifiedScope(row)">供应商未注明型号</template><template v-else-if="row.phoneScopeMode==='unresolved'||!row.phoneModelIds.length">型号资料待关联</template><template v-else-if="!allowedModels(row).length">当前范围暂无可选型号</template><template v-else>本款可选 {{allowedModels(row).length}} 个型号</template> · 本次选 {{chosen[catalogKey(row)]?.length||0}} 个</small></div></header>
            <div class="selection-row-chosen">
              <el-tag v-for="id in (chosen[catalogKey(row)]||[]).slice(0,2)" :key="id" size="small" :closable="canWrite&&!busy&&!unknown" @close="changeModels(row,(chosen[catalogKey(row)]||[]).filter(value=>value!==id))">{{phoneName(row,id)}}</el-tag>
              <el-button v-if="(chosen[catalogKey(row)]?.length||0)>2" link @click="expanded[catalogKey(row)]=true">共 {{chosen[catalogKey(row)].length}} 个，展开</el-button>
              <span v-if="!chosen[catalogKey(row)]?.length" class="selection-row-empty">{{excluded[catalogKey(row)]?'本次不选':'未选型号'}}</span>
              <small v-if="adjusted[catalogKey(row)]">已单独调整</small>
            </div>
            <div class="selection-row-quantity"><label>拟拿样数量（本款合计）</label><el-input-number :model-value="sampleQuantities[catalogKey(row)]" :key="catalogKey(row)+':qty:'+(busy||unknown||!canWrite||excluded[catalogKey(row)])" :min="0" :max="Number.MAX_SAFE_INTEGER" :precision="0" :controls="false" placeholder="未定" :aria-label="row.name+'拟拿样数量'" :disabled="busy||unknown||!canWrite||excluded[catalogKey(row)]" @update:model-value="changeQuantity(row,$event)"/><el-button v-if="quantityAdjusted[catalogKey(row)]" link :disabled="busy||unknown||!canWrite" @click="followQuantity(row)">随统一数量</el-button></div>
            <el-button class="selection-adjust-button" link :disabled="busy||unknown||!canWrite" :aria-expanded="!!expanded[catalogKey(row)]" @click="expanded[catalogKey(row)]=!expanded[catalogKey(row)]">{{expanded[catalogKey(row)]?'收起':'调整'}}</el-button>
          </div>
          <div v-show="expanded[catalogKey(row)]" class="selection-row-adjust">
          <div class="selection-adjust-actions"><strong>本款型号与颜色</strong><el-button v-if="adjusted[catalogKey(row)]" link :disabled="busy||unknown||!canWrite" @click="restoreBatch(row)">恢复统一选择</el-button><el-button v-if="!excluded[catalogKey(row)]" link :disabled="busy||unknown||!canWrite" @click="changeModels(row,[])">本次不选</el-button></div>
          <p v-if="row.modelText" class="selection-source-models"><span>来源型号：</span>{{row.modelText}}</p>
          <div v-if="modelFamilies[catalogKey(row)]?.length" class="selection-series-shortcuts" aria-label="本款系列快捷选择"><el-button v-for="family in modelFamilies[catalogKey(row)]" :key="family.key" size="small" :type="familyChosen(row,family.phoneModelIds)?'primary':undefined" plain :disabled="busy||unknown||!canWrite" @click="toggleFamily(row,family.phoneModelIds)">{{familyChosen(row,family.phoneModelIds)?'取消':'选择'}}{{family.name}}（{{family.phoneModelIds.length}}）</el-button></div>
          <PhoneModelMultiSelect v-if="unspecifiedScope(row)||allowedModels(row).length" :model-value="chosen[catalogKey(row)]||[]" :members="row.phones" :allowed-ids="selectorScope(row)" :disabled="busy||unknown||!canWrite||row.phoneScopeMode==='unresolved'" :aria-label="row.name+'本次所选系列／型号'" :placeholder="unspecifiedScope(row)?'从现有手机资料选择系列／型号':'选择系列／型号（仅本款可选范围）'" @update:model-value="changeModels(row,$event)" @selected="rememberPhones(row,$event)"/>
          <details v-if="knownPairs(row).length" class="selection-pairs"><summary>颜色／配置（选填）</summary><el-checkbox-group :model-value="pairs[catalogKey(row)]||[]" :disabled="busy||unknown||!canWrite" @update:model-value="changePairs(row,$event as string[])"><el-checkbox v-for="pair in knownPairs(row)" :key="pairKey(pair)" :value="pairKey(pair)">{{phoneName(row,pair.phoneModelId)}} · {{pair.colorSnapshot!.name}}<span v-if="pair.state==='unverified'">（待核实）</span></el-checkbox></el-checkbox-group></details>
          <p v-else-if="row.color" class="selection-color">供方颜色：{{row.color}}（选填，不自动组合）</p>
          </div>
          <p v-if="unspecifiedScope(row)&&!excluded[catalogKey(row)]" class="selection-scope-note selection-row-notice">供应商未注明型号，可从现有资料选择；不代表已确认可供。</p>
          <p v-if="!excluded[catalogKey(row)]&&(row.phoneScopeMode==='unresolved'||!unspecifiedScope(row)&&!allowedModels(row).length)" class="selection-issue selection-row-notice">{{row.phoneScopeMode==='unresolved'||!row.phoneModelIds.length?'本款型号资料尚未提取或关联，请先核对来源型号表。':'本款型号已明确不供，暂无可选型号。'}}</p>
          <p v-if="!excluded[catalogKey(row)]&&chosen[catalogKey(row)]?.some(id=>!modelAllowed(row,id))" class="selection-issue selection-row-notice">本次型号不在当前允许范围或资料已变化，请重新选择。</p>
          <p v-if="unmatched[catalogKey(row)]?.length" class="selection-scope-note selection-row-notice">未选入：{{phoneList(row,unmatched[catalogKey(row)],batchPhones)}}（不在本款可选范围或已停用）</p>
          <section v-if="progressPhones(row).length" class="selection-supplier-progress"><strong>本次型号的供方进度</strong><p v-for="id in visibleProgress(row)" :key="id"><b>{{phoneName(row,id)}}</b><span v-for="status in catalogPhoneProgress(row,id)" :key="status">{{status}}</span></p><el-pagination v-if="progressPhones(row).length>6" :current-page="progressPage(row)" :page-size="6" :total="progressPhones(row).length" :pager-count="5" layout="prev,pager,next" @current-change="progressPages[catalogKey(row)]=$event"/><small>原进度如实保留，不等于本批现货、实收或质检结论。</small></section>
        </article>
        <p v-if="batchMessage" class="selection-batch-message" role="status">{{batchMessage}}</p>
        <p v-if="pendingRows.length&&combinationCount" class="selection-scope-note">{{pendingRows.length}} 款尚未选择型号，请点击对应“调整”补选，或明确选择“本次不选”。</p>
      </section>
      <div v-if="validationIssues.length" class="selection-errors" role="alert"><p v-for="message in validationIssues" :key="message" class="selection-issue">{{message}}</p></div>
      <p class="selection-note">{{targetSelectionId?'本次增选直接保存到原选品目录。':'直接保存到本供应商的选品目录。'}}收到实物再登记收样，不会提前生成产品或样品批次。</p>
    </div>
    <template #footer><div class="selection-total" aria-live="polite"><strong>已选 {{selectedStyleCount}} 款</strong><small>{{unplannedCount===selectedItems.length?'拟拿样数量未填':'拟拿样合计 '+plannedTotal+' 件'}}<template v-if="unplannedCount"> · {{unplannedCount}} 款未填数量</template>，不按型号数相乘</small></div><el-button :disabled="busy||closing||unknown" @click="close()">取消</el-button><el-button type="primary" :disabled="!canWrite||busy||closing||(!unknown&&!valid)" :loading="busy" @click="submit">{{unknown?'核对保存结果':'保存选样'}}</el-button></template>
  </el-dialog>
</template>

<style scoped>
:global(.catalog-selection-dialog-shell){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-bottom:20px;padding:0;--el-component-size:28px;font-size:12px}
:global(.catalog-selection-dialog-shell>.el-dialog__header){padding:16px 22px 0;margin:0;flex:none}
:global(.catalog-selection-dialog-shell .el-dialog__title){font-size:16px;font-weight:600;color:var(--el-text-color-primary)}
:global(.catalog-selection-dialog-shell>.el-dialog__body){padding:10px 22px 16px;min-height:0;overflow:auto}
:global(.catalog-selection-dialog-shell>.el-dialog__footer){display:flex;justify-content:flex-end;align-items:center;gap:8px;flex-wrap:wrap;padding:12px 22px;background:var(--el-fill-color-light);border-top:1px solid var(--el-border-color-lighter);flex:none}
:global(.catalog-selection-dialog-shell>.el-dialog__footer>.el-button){margin:0;font-size:12px;min-height:28px;height:auto;white-space:normal}
.catalog-selection-dialog{display:flex;flex-direction:column;gap:10px;min-width:0;container-type:inline-size;font-size:12px;color:var(--el-text-color-regular)}
.catalog-selection-dialog :deep(.el-button),.catalog-selection-dialog :deep(.el-input__inner){font-size:12px}
.catalog-selection-dialog :deep(.el-button){margin-left:0;min-height:28px;height:auto;white-space:normal;overflow-wrap:anywhere}
.catalog-selection-dialog h3{margin:0;font-size:13px;color:var(--el-text-color-primary);font-weight:600}
.selection-intro,.selection-note{margin:0;font-size:12px;line-height:1.6;overflow-wrap:anywhere}
.selection-intro,.selection-note{color:var(--el-text-color-secondary)}
.selection-batch{padding:14px 16px;background:var(--el-fill-color-light);border:1px solid var(--el-border-color-lighter);border-radius:7px}
.selection-section-heading{display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:10px}
.selection-section-heading>span{color:var(--el-text-color-secondary)}
.selection-batch-controls{display:flex;gap:8px;align-items:flex-start}
.selection-batch-picker{flex:1;min-width:0}
.selection-batch-shortcuts{display:flex;align-items:center;gap:6px 12px;flex-wrap:wrap;margin-top:8px}
.selection-batch-shortcuts>span{color:var(--el-text-color-secondary)}
.selection-batch-shortcuts .selection-clear{margin-left:auto;color:var(--el-text-color-secondary)}
.selection-check-heading{margin-top:20px}
.selection-table-heading,.selection-row-main{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.3fr) 132px 44px;gap:12px;align-items:center;padding:9px 10px}
.selection-table-heading{background:var(--el-fill-color-light);color:var(--el-text-color-secondary);border-block:1px solid var(--el-border-color-lighter);font-weight:600}
.selection-model-row{border-bottom:1px solid var(--el-border-color-lighter);min-width:0}
.selection-row-main{padding-block:13px}
.selection-model-row header{display:flex;align-items:center;gap:10px;min-width:0}
.selection-model-row header>div{min-width:0;overflow-wrap:anywhere}
.selection-model-row strong{font-size:12px;font-weight:600;color:var(--el-text-color-primary)}
.selection-model-row small{display:block;color:var(--el-text-color-secondary);font-size:12px;margin-top:3px;line-height:1.5}
.selection-model-row :deep(.partner-photo),.selection-photo-placeholder{width:40px;height:42px;flex:none;border-radius:5px}
.selection-photo-placeholder{display:grid;place-items:center;background:var(--el-fill-color-light);border:1px solid var(--el-border-color-lighter);color:var(--el-text-color-secondary);font-size:11px;text-align:center}
.selection-row-chosen{display:flex;gap:5px;flex-wrap:wrap;min-width:0;align-items:center}
.selection-row-chosen small{width:100%;margin:0}
.selection-row-chosen :deep(.el-tag){max-width:100%;height:auto;min-height:24px}
.selection-row-chosen :deep(.el-tag__content){white-space:normal;line-height:18px;overflow-wrap:anywhere}
.selection-row-empty{color:var(--el-text-color-secondary)}
.selection-adjust-button{color:var(--el-color-primary)}
.selection-row-adjust{padding:10px 14px 14px;background:var(--el-fill-color-light);border-top:1px dashed var(--el-border-color-lighter);min-width:0}
.selection-adjust-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:8px}
.selection-adjust-actions>strong{margin-right:auto}
.selection-source-models,.selection-scope-note{margin:0;font-size:12px;line-height:1.6;white-space:pre-wrap;overflow-wrap:anywhere}
.selection-source-models{margin-bottom:7px}
.selection-source-models>span,.selection-scope-note{color:var(--el-text-color-secondary)}
.selection-row-notice{padding:0 10px 9px;margin:0}
.selection-series-shortcuts{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:7px}
.selection-series-shortcuts :deep(.el-button){max-width:100%;line-height:1.5}
.selection-batch-message{margin:10px 0 0;color:var(--el-text-color-secondary);line-height:1.6}
.selection-supplier-progress{margin:0 10px 9px;padding:7px;background:var(--el-fill-color-light);border-radius:4px;font-size:12px;min-width:0}
.selection-supplier-progress>p{display:flex;gap:3px 10px;flex-wrap:wrap;margin:4px 0;overflow-wrap:anywhere;line-height:1.6}
.selection-supplier-progress>p>b{font-weight:500}
.selection-supplier-progress :deep(.el-pagination){max-width:100%;flex-wrap:wrap}
.selection-issue{color:var(--el-color-danger);font-size:12px;line-height:1.6;overflow-wrap:anywhere}
.selection-pairs{margin-top:8px;font-size:12px}
.selection-pairs :deep(.el-checkbox){display:flex;margin:3px 0;height:auto;white-space:normal}
.selection-pairs :deep(.el-checkbox__label){white-space:normal;overflow-wrap:anywhere;font-size:12px}
.selection-quantity-batch{display:flex;align-items:center;flex-wrap:wrap;gap:6px 10px;margin-top:10px}.selection-quantity-batch>.el-input-number{width:90px}.selection-quantity-batch small{color:var(--el-text-color-secondary)}.selection-row-quantity{min-width:0;display:flex;flex-direction:column;gap:4px}.selection-row-quantity>label{font-size:11px;color:var(--el-text-color-secondary);line-height:1.5}.selection-row-quantity :deep(.el-input-number){width:100%}.selection-row-quantity :deep(.el-button){align-self:flex-start;font-size:11px}.selection-color{color:var(--el-text-color-secondary)}
.selection-total{display:flex;flex-direction:column;gap:3px;margin-right:auto;text-align:left;font-size:12px;line-height:1.5}
.selection-total strong{font-size:13px;color:var(--el-text-color-primary);font-weight:600}
.selection-total small{font-size:12px;color:var(--el-text-color-secondary)}
@container(max-width:570px){.selection-table-heading{display:none}.selection-row-main{grid-template-columns:minmax(0,1fr) 44px;gap:8px}.selection-model-row header{grid-column:1/-1}.selection-row-chosen{padding-left:50px}.selection-row-quantity{grid-column:1;padding-left:50px}.selection-adjust-button{grid-column:2;grid-row:2/4}.selection-batch-controls{flex-wrap:wrap}.selection-batch-picker{flex-basis:100%}}
@media(max-width:600px){:global(.catalog-selection-dialog-shell>.el-dialog__body){padding-inline:14px}:global(.catalog-selection-dialog-shell>.el-dialog__header){padding-inline:14px}:global(.catalog-selection-dialog-shell>.el-dialog__footer){padding-inline:14px}.selection-total{width:100%}.selection-batch{padding:12px}}
</style>

