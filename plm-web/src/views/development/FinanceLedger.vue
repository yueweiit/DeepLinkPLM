<script setup lang="ts">
import { t as uiText } from '@/locales'

import PhoneObjectIdSelect from '@/components/PhoneObjectSelect/IdSelect.vue'
import type { PhoneObjectOption } from '@/components/PhoneObjectSelect/types'
import { computed, onActivated, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useFinanceWorkspace } from '@/stores/financeWorkspace'
import { useCachedRequest } from '@/composables/useCachedRequest'
import { createFinanceRow, exportBudgetFile, getFinanceHistory, getFinanceOptions, getFinanceRows, getFinanceSummary, type FinanceEntity, type FinanceHistory, type FinanceOptions, type FinanceQuery, type FinanceResult, type FinanceRow, type FinanceValue } from '@/api/modules/development'
import { editFinanceCell, financeValue, parseFinancePaste, previewFormula, previewBreakEvenQuantity } from './financeDrafts'
import FinanceImportDialog from './FinanceImportDialog.vue'
import FixedTableViewport from '@/components/FixedTableViewport/index.vue'
const props = defineProps<{ entity: FinanceEntity; projectId?: number }>()
const emit = defineEmits<{changed: []}>()
const route=useRoute()
const currentProjectId=computed(()=>props.projectId||Number(route.query.projectId)||undefined)
const user = useUserStore()
const query = reactive<FinanceQuery>({ page: 1, size: 50, kind: props.entity === 'budget' ? (route.query.kind==='platform'?'platform':'mold') : undefined, productId: props.projectId,projectId:props.projectId?undefined:Number(route.query.projectId)||undefined, budgetId:Number(route.query.budgetId)||undefined,expenseId:Number(route.query.expenseId)||undefined })
const keyword = ref(''), loadingAction = ref(false), importOpen = ref(false), createOpen = ref(false), saveOpen = ref(false), historyOpen = ref(false), conflictOpen = ref(false)
const workspace = useFinanceWorkspace()
const ledger = workspace.forEntity(props.entity)
const { drafts, overrides } = ledger
const reason = ref(''), conflicts = ref<FinanceResult[]>([])
const options = ref<FinanceOptions>({ productLines: [], items: [], molds: [], budgets: [], writable: false, confirmable: false, exportable: false })
const history = ref<FinanceHistory[]>([]), historyId = ref(0), historyPage = ref(1), historyTotal = ref(0)
const summary = ref<{ currencyGroups: Record<string, FinanceValue>[]; rule: string }>({ currencyGroups: [], rule: '' })
const cache = useCachedRequest((q: FinanceQuery) => getFinanceRows(props.entity, q))
const rows = computed(() => user.token && cache.hasCurrentData.value ? (cache.data.value?.content || []).map(r => overrides.get(id(r)) || r) : [])
const title = computed(() => props.entity === 'budget' ? '模具成本预算' : '开发费用归集')
const changedCount = computed(() => [...drafts.values()].reduce((count, d) => count + d.changes.size, 0))
const itemObjects=computed<PhoneObjectOption[]>(()=>options.value.items.map(i=>({type:'item',id:i.itemId,name:i.compatibleModels,code:`#${i.itemId}`,productLineId:i.productLineId||undefined,productLineName:i.styleName,phoneModels:i.phoneModels||[]})))
const form = reactive({ name: '', productLineId: null as number | null, itemId: null as number | null, moldSetId: null as number | null, parentBudgetId: null as number | null, budgetId: null as number | null, currency: 'CNY' })
const moneyFields = computed(() => props.entity === 'budget' ? [
  { key: 'budgetAmount', label: '手工预算', width: 150 }, { key: 'moldCost', label: '模具成本', width: 150 }, { key: 'moldWeight', label: '模具重量 KG', width: 145 }, { key: 'transportRate', label: '运输 原币/KG', width: 150 }, { key: 'fuelRate', label: '燃油 原币/KG', width: 150 }, { key: 'customsCost', label: '清关成本', width: 150 }, { key: 'salePrice', label: '产品单价', width: 150 }, { key: 'unitProductionCost', label: '单件生产成本', width: 160 }
] : [{ key: 'requestedAmount', label: '审批申请金额', width: 170 }, { key: 'approvedAmount', label: '审批通过金额', width: 170 }, { key: 'declaredAmount', label: '手工申报费用', width: 170 }])
const pasteFields = computed(() => moneyFields.value.map(f => f.key))
function id(row: FinanceRow) { return Number(props.entity === 'budget' ? row.budgetId : row.expenseId) }
function value(row: FinanceRow, key: string): FinanceValue { return drafts.get(id(row))?.changes.get(key)?.after ?? (drafts.get(id(row))?.changes.has(key) ? null : row[key] ?? null) }
function effective(row: FinanceRow) { const next = { ...row }; drafts.get(id(row))?.changes.forEach(c => next[c.field] = c.after); return next }
function display(v: FinanceValue | undefined) { return v == null ? '未知' : String(v) }
function breakEven(row:FinanceRow){return previewBreakEvenQuantity(effective(row))}
function editable(row: FinanceRow, key: string) { if (!options.value.writable || ledger.saving) return false; if (props.entity === 'budget') return true; if (row.sourceSystem === 'erp') return ['notes','budgetId','itemId','moldSetId','productLineId'].includes(key) && options.value.confirmable; if (row.state !== 'draft') return ['notes','state','budgetId','itemId','moldSetId','productLineId'].includes(key) && options.value.confirmable; return !['approvedAmount','approvalStatus','state'].includes(key) || options.value.confirmable }
function edit(row: FinanceRow, key: string, input: unknown, numeric = false) { try { if (!editable(row, key)) return; const after = input == null ? null : typeof input === 'number' ? input : financeValue(String(input), numeric); editFinanceCell(drafts, id(row), row, key, after) } catch (e) { ElMessage.warning(e instanceof Error ? e.message : uiText('格式错误')) } }
function paste(event: ClipboardEvent, row: FinanceRow, field: string) { const text = event.clipboardData?.getData('text/plain') || ''; if (!/[\t\n]/.test(text)) return; event.preventDefault(); const cells = parseFinancePaste(text), startRow = rows.value.findIndex(r => id(r) === id(row)), startCol = pasteFields.value.indexOf(field); if (startCol < 0) return
  try { cells.forEach((line, offset) => line.forEach((cell, col) => { const target = rows.value[startRow + offset], key = pasteFields.value[startCol + col]; if (target && key && editable(target,key)) editFinanceCell(drafts,id(target),target,key,financeValue(cell,true)) })); ElMessage.success(uiText('已粘贴到当前页草稿，尚未保存')) } catch (e) { ElMessage.warning(e instanceof Error ? e.message : uiText('粘贴格式错误')) } }
let searchTimer: ReturnType<typeof setTimeout> | undefined
let generation = 0
async function load(force = false) { if (!user.token) return; const active = ++generation, token = user.token, revision = ledger.revision, q = { ...query }; await cache.execute(q, force); if (active!==generation||token!==user.token||revision!==ledger.revision||cache.error.value) return; cache.data.value?.content.forEach(row=>overrides.delete(id(row))); try { const result=await getFinanceSummary(props.entity,q);if(active===generation&&token===user.token&&revision===ledger.revision)summary.value=result } catch { /* independent load status */ } }
async function loadOptions() { const token=user.token;if(!token)return;const result=await getFinanceOptions();if(user.token===token)options.value=result }
async function refresh() { await load(true); await loadOptions() }
watch(keyword, text => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { query.keyword = text.trim() || undefined; query.page = 1; void load() },300) })
watch(() => [query.kind,query.state,query.currency,query.productLineId], () => { query.page = 1; void load() })
watch(()=>[route.query.projectId,route.query.budgetId,route.query.expenseId,route.query.kind],()=>{if(!props.projectId){query.projectId=Number(route.query.projectId)||undefined;query.budgetId=Number(route.query.budgetId)||undefined;query.expenseId=Number(route.query.expenseId)||undefined;if(props.entity==='budget')query.kind=route.query.kind==='platform'?'platform':'mold';query.page=1;void load()}})
watch(()=>props.projectId,id=>{query.productId=id;query.page=1;void load()})
watch(() => ledger.revision, () => { generation++;cache.clear();if(user.token)void load(true) }, { flush: 'sync' })
watch(() => user.token, () => { generation++;cache.clear(); cache.data.value = undefined; cache.hasCurrentData.value = false; summary.value = { currencyGroups: [], rule: '' }; options.value = { productLines: [],items: [],molds: [],budgets: [],writable: false,confirmable: false,exportable: false }; history.value = []; conflicts.value = [] })
onMounted(async () => { await loadOptions(); await load() })
onActivated(() => { if (cache.data.value) void load() })
onBeforeUnmount(() => { clearTimeout(searchTimer) })
onBeforeRouteLeave(async () => { if (!drafts.size) return true; try { await ElMessageBox.confirm(uiText('有未保存修改。离开后保留会话草稿，是否继续？'),uiText('未保存内容'),{ type: 'warning',confirmButtonText: uiText('保留草稿并离开'),cancelButtonText: uiText('继续填写') }); return true } catch { return false } })
function changePage() { void load() }
function changeSize() { query.page = 1; void load() }
function undo() { if(ledger.saving){ElMessage.info(uiText('正在保存，请等待回执后再撤销；已发出的保存不能撤回'));return}drafts.clear(); conflicts.value = []; ElMessage.info(uiText('本模块所有会话草稿已撤销，已保存历史不变')) }
function openCreate() { Object.assign(form,{ name:'',productLineId:options.value.productLines.some(p=>p.productId===currentProjectId.value)?currentProjectId.value:null,itemId:null,moldSetId:null,parentBudgetId:null,budgetId:null,currency:'CNY' }); createOpen.value=true }
function itemChanged() { form.productLineId = options.value.items.find(i => i.itemId === form.itemId)?.productLineId ?? null; form.moldSetId = null }
function mapExpenseItem(row:FinanceRow,itemId:unknown) {edit(row,'itemId',itemId);const item=options.value.items.find(i=>i.itemId===Number(itemId));edit(row,'productLineId',item?.productLineId??null);edit(row,'moldSetId',null);edit(row,'budgetId',null)}
async function create() { loadingAction.value=true; try { await createFinanceRow(props.entity,props.entity==='budget'?{kind:query.kind,name:form.name,productLineId:form.productLineId,itemId:query.kind==='mold'?form.itemId:null,moldSetId:query.kind==='mold'?form.moldSetId:null,parentBudgetId:query.kind==='mold'?form.parentBudgetId:null,currency:form.currency}:{description:form.name,productLineId:form.productLineId,itemId:form.itemId,moldSetId:form.moldSetId,budgetId:form.budgetId,currency:form.currency,productId:currentProjectId.value});createOpen.value=false;await refresh();emit('changed');ElMessage.success(uiText('已创建，未知金额留空；在表格中填写后批量保存')) } finally { loadingAction.value=false } }
async function save() { if(props.entity==='budget'&&!reason.value.trim()){ElMessage.warning(uiText('请填写预算修订原因'));return} const token=user.token;loadingAction.value=true;try { const results=await workspace.save(props.entity,reason.value.trim());if(!results||token!==user.token)return;conflicts.value=results.filter(r=>r.status==='conflict');const saved=results.filter(r=>r.status==='saved').length;saveOpen.value=false;if(conflicts.value.length)conflictOpen.value=true;ElMessage.info(uiText('保存 {value0} 行；未提交及失败修改仍保留草稿',{value0:(saved)}));if(saved)emit('changed')
  } finally {if(token===user.token)loadingAction.value=false} }
function rebase(result:FinanceResult) {const d=drafts.get(result.id);if(!d||!result.row)return;result.conflicts.forEach(c=>{const change=d.changes.get(c.field);if(change)change.before=c.current});d.source={...result.row};overrides.set(result.id,result.row);d.error='已人工核对当前值，等待重新保存';conflicts.value=conflicts.value.filter(c=>c.id!==result.id);if(!conflicts.value.length)conflictOpen.value=false}
async function showHistory(row:FinanceRow){historyId.value=id(row);historyPage.value=1;historyOpen.value=true;await loadHistory()}
async function loadHistory(){const token=user.token,key=historyId.value,page=historyPage.value;const result=await getFinanceHistory(props.entity,key,page);if(token!==user.token||key!==historyId.value||page!==historyPage.value)return;history.value=result.content;historyTotal.value=result.totalElements}
async function exportFile(){const file=await exportBudgetFile({...query});const url=URL.createObjectURL(file),link=document.createElement('a');link.href=url;link.download='模具成本预算.xlsx';link.click();URL.revokeObjectURL(url)}
const groupLabel = (g:Record<string,FinanceValue>) => `${g.currencyCode || ''}${g.budgetKind === 'platform' ? ' 平台预算' : g.budgetKind === 'mold' ? ' 模具预算' : ''}`
</script>
<template>
  <main class="finance-ledger">
    <header><div><h2>{{ title }}</h2><p>{{ entity==='budget'?uiText('按平台或款式＋兼容型号档案核算，不按颜色重复预算；运输74.68、燃油33.18 CNY/KG为已确认模板费率。'):uiText('复用原项目费用台账。审批申请、审批通过、手工申报、ERP已过账金额独立，原币分组，不与BOM单价混算。') }}</p></div><div class="actions"><el-button @click="refresh">{{ uiText("刷新") }}</el-button><el-button v-if="entity==='budget'&&options.exportable" @click="exportFile">{{ uiText("导出预算模板") }}</el-button><el-button v-if="options.writable" @click="importOpen=true">{{ entity==='budget'?uiText('Excel预览导入'):uiText('ERP明细核对') }}</el-button><el-button v-if="options.writable" type="primary" @click="openCreate">{{ entity==='budget'?uiText('新增预算'):uiText('新增费用草稿') }}</el-button></div></header>
    <el-tabs v-if="entity==='budget'" v-model="query.kind"><el-tab-pane :label='uiText("模具预算（档案 / 明确套次）")' name="mold"/><el-tab-pane :label='uiText("平台预算（款式投资计划）")' name="platform"/></el-tabs>
    <div class="summary" v-if="summary.currencyGroups.length"><div v-for="g in summary.currencyGroups" :key="groupLabel(g)"><strong>{{ uiText(String((groupLabel(g)) ?? '')) }}</strong><span v-if="entity==='budget'">{{ uiText("预算 ") }}{{ display(g.budgetTotal) }}{{ uiText(" · 未知 ") }}{{ g.unknownCount }}{{ uiText(" 条") }}</span><span v-else>{{ uiText("申请 ") }}{{ display(g.requestedTotal) }}{{ uiText(" · 通过 ") }}{{ display(g.approvedTotal) }}{{ uiText(" · 申报 ") }}{{ display(g.declaredTotal) }}{{ uiText(" · ERP净额 ") }}{{ display(g.erpPostedNet) }}</span></div></div>
    <el-alert :closable="false" type="info" :title="summary.rule || uiText('空值表示未知，0表示已确认零值。预算修改保留版本与原因；刷新不会覆盖草稿。')" />
    <div class="filters"><el-input v-model="keyword" :placeholder='uiText("搜索预算 / 款式 / 手机型号 / ERP单据")' clearable class="search"/><el-select v-model="query.productLineId" filterable clearable :placeholder='uiText("全部产品平台")'><el-option v-for="p in options.productLines" :key="p.productId" :label="p.productName" :value="p.productId"/></el-select><el-select v-model="query.currency" clearable :placeholder='uiText("全部原币")'><el-option v-for="c in ['CNY','MXN','USD','EUR']" :key="c" :value="c" :label="c"/></el-select><el-select v-if="entity==='expense'" v-model="query.state" clearable :placeholder='uiText("全部状态")'><el-option value="draft" :label='uiText("草稿")'/><el-option value="confirmed" :label='uiText("已确认")'/><el-option value="void" :label='uiText("已作废")'/></el-select><span class="draft-count">{{ changedCount }}{{ uiText(" 个待保存单元格") }}</span><el-button :disabled="!drafts.size" @click="undo">{{ uiText("撤销未保存") }}</el-button><el-button type="primary" :disabled="!drafts.size" @click="reason='';saveOpen=true">{{ uiText("批量保存") }}</el-button></div>
    <p v-if="cache.error.value" class="error">{{ cache.error.value }}</p>
    <FixedTableViewport v-slot="{tableHeight}" :fill="!props.projectId"><el-table :data="rows" :row-key="id" border stripe :height="tableHeight" class="wide-table" v-loading="cache.loading.value">
      <el-table-column :label='uiText("核算对象 / 名称")' fixed min-width="280"><template #default="{ row }"><strong>{{ row.styleName || row.productLineName || uiText('历史项目费用（归属待核对）') }}</strong><div>{{ row.compatibleModels || uiText('平台级，不推断手机型号或实物套') }}</div><el-input :model-value="value(row,entity==='budget'?'budgetName':'description')" :disabled="!editable(row,entity==='budget'?'budgetName':'description')" @update:model-value="edit(row,entity==='budget'?'budgetName':'description',$event)"/><small>#{{ id(row) }} · {{ row.moldSetId ? uiText('模具套 {value0}',{value0:(row.moldSetId)}) : uiText('共享档案/平台') }}<span v-if="entity==='budget'">{{ uiText(" · 版本 ") }}{{ row.versionNo }}</span></small><p v-if="drafts.get(id(row))?.error" class="error">{{ drafts.get(id(row))?.error }}</p></template></el-table-column>
      <el-table-column :label='uiText("原币")' width="82"><template #default="{ row }">{{ row.currencyCode }}</template></el-table-column>
      <el-table-column v-if="entity==='budget'" :label='uiText("测算方式")' width="165"><template #default="{ row }"><select class="cell-select" :value="value(row,'calculationMode')" :disabled="!editable(row,'calculationMode')" @change="edit(row,'calculationMode',($event.target as HTMLSelectElement).value)"><option value="manual">{{ uiText("手工预算上限") }}</option><option value="mold_formula">{{ uiText("原表开模公式") }}</option></select></template></el-table-column>
      <el-table-column v-for="f in moneyFields" :key="f.key" :label="uiText(f.label)" :width="f.width"><template #default="{ row }"><el-input :model-value="value(row,f.key)" :data-field="f.key" :data-row="id(row)" :class="{dirty:drafts.get(id(row))?.changes.has(f.key)}" :disabled="!editable(row,f.key)" :placeholder='uiText("未知留空")' inputmode="decimal" @update:model-value="edit(row,f.key,$event,true)" @paste="paste($event,row,f.key)"/></template></el-table-column>
      <el-table-column v-if="entity==='budget'" :label='uiText("合计 / 回本订单数量")' width="190"><template #default="{ row }"><strong>{{ display(previewFormula(effective(row))) }}</strong><div v-if="breakEven(row)!=null">{{ breakEven(row) }}{{ uiText(" 件") }}</div><div v-else>{{ uiText("未知或单件利润不为正") }}</div><small>{{ uiText("估算，非ERP实际入账") }}</small></template></el-table-column>
      <el-table-column v-if="entity==='budget'" :label='uiText("已关联审批 / ERP净额")' width="235"><template #default="{row}"><div>{{ uiText("申请 ") }}{{ display(row.requestedTotal) }}</div><div>{{ uiText("通过 ") }}{{ display(row.approvedTotal) }}</div><strong>{{ uiText("ERP净额 ") }}{{ display(row.erpPostedNet) }}</strong><div>{{ uiText("预算剩余 ") }}{{ display(row.remainingBudget) }}</div><small>{{ uiText("仅本预算稳定ID关联；不叠加父/子预算") }}</small></template></el-table-column>
      <el-table-column v-if="entity==='expense'" :label='uiText("核对共享档案归属")' width="275"><template #default="{row}"><PhoneObjectIdSelect :model-value="Number(value(row,'itemId'))||null" :disabled="!editable(row,'itemId')" :options="itemObjects" type="item" :label='uiText("费用归属模具档案")' @update:model-value="mapExpenseItem(row,$event)"/><el-select :model-value="value(row,'moldSetId')" :disabled="!editable(row,'moldSetId')" clearable :placeholder='uiText("明确套次（可选）")' @update:model-value="edit(row,'moldSetId',$event)"><el-option v-for="s in options.molds.filter(s=>s.itemId===Number(value(row,'itemId')))" :key="s.moldSetId" :value="s.moldSetId" :label="`${s.setNo} · ${s.setName}`"/></el-select></template></el-table-column>
      <el-table-column v-if="entity==='expense'" :label='uiText("费用分类 / 供应商")' width="220"><template #default="{row}"><el-select :model-value="value(row,'expenseCategory')" :disabled="!editable(row,'expenseCategory')" @update:model-value="edit(row,'expenseCategory',$event)"><el-option value="mold" :label='uiText("模具费用")'/><el-option value="sample" :label='uiText("样品费用")'/><el-option value="design" :label='uiText("设计费用")'/><el-option value="promotion" :label='uiText("推广费用")'/><el-option value="other" :label='uiText("其他开发费用")'/></el-select><el-input :model-value="value(row,'supplierName')" :disabled="!editable(row,'supplierName')" maxlength="128" :placeholder='uiText("供应商（未知可留空）")' @update:model-value="edit(row,'supplierName',$event)"/></template></el-table-column>
      <el-table-column v-if="entity==='expense'" :label='uiText("费用发生时间")' width="230"><template #default="{row}"><el-date-picker :model-value="value(row,'occurredAt')" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :disabled="!editable(row,'occurredAt')" :placeholder='uiText("发生时间（未知留空）")' @update:model-value="edit(row,'occurredAt',$event)"/></template></el-table-column>
      <template v-if="entity==='expense'"><el-table-column :label='uiText("审批状态 / 实例ID")' width="215"><template #default="{ row }"><el-select :model-value="value(row,'approvalStatus')" :disabled="!editable(row,'approvalStatus')" @update:model-value="edit(row,'approvalStatus',$event)"><el-option value="unknown" :label='uiText("未提供审批")'/><el-option value="requested" :label='uiText("申请中")'/><el-option value="approved" :label='uiText("通过（需真实ID）")'/><el-option value="rejected" :label='uiText("驳回")'/></el-select><el-input :model-value="value(row,'approvalInstanceId')" :disabled="!editable(row,'approvalInstanceId')" :placeholder='uiText("真实审批实例ID")' @update:model-value="edit(row,'approvalInstanceId',$event)"/></template></el-table-column><el-table-column :label='uiText("ERP已过账 / 来源")' width="245"><template #default="{ row }"><strong>{{ row.sourceSystem==='erp'?display(row.erpPostedAmount):uiText('未入账（不能据申报推断）') }}</strong><div>{{ row.erpVoucherId }} {{ row.erpLineId }}</div><small v-if="row.erpReversalOf">{{ uiText("冲销：") }}{{ row.erpReversalOf }}</small></template></el-table-column><el-table-column :label='uiText("预算归属（核对）")' width="230"><template #default="{ row }"><el-select :model-value="value(row,'budgetId')" filterable clearable :disabled="!editable(row,'budgetId')" @update:model-value="edit(row,'budgetId',$event)"><el-option v-for="b in options.budgets.filter(b=>b.currencyCode===row.currencyCode)" :key="b.budgetId" :value="b.budgetId" :label="String(b.budgetName)"/></el-select></template></el-table-column><el-table-column :label='uiText("费用状态")' width="140"><template #default="{ row }"><el-select :model-value="value(row,'state')" :disabled="!editable(row,'state')||row.sourceSystem==='erp'" @update:model-value="edit(row,'state',$event)"><el-option value="draft" :label='uiText("草稿")'/><el-option value="confirmed" :label='uiText("确认申报")'/><el-option value="void" :label='uiText("作废")'/></el-select></template></el-table-column></template>
      <el-table-column :label='uiText("备注")' min-width="300"><template #default="{ row }"><el-input :model-value="value(row,'notes')" type="textarea" :rows="2" :disabled="!editable(row,'notes')" @update:model-value="edit(row,'notes',$event)"/></template></el-table-column><el-table-column :label='uiText("历史")' width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="showHistory(row)">{{ uiText("历史") }}</el-button></template></el-table-column>
    </el-table></FixedTableViewport>
    <footer><span>{{ uiText("筛选后 ") }}{{ cache.data.value?.totalElements || 0 }}{{ uiText(" 条 · 仅渲染当前页") }}</span><el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :page-sizes="[20,50,100]" :total="cache.data.value?.totalElements || 0" layout="total,sizes,prev,pager,next,jumper" @current-change="changePage" @size-change="changeSize"/></footer>
    <el-dialog v-model="createOpen" :title="uiText('新增{value0}',{value0:(entity==='budget'?'预算':'费用草稿')})" width="900px"><el-form label-position="top" class="create-grid"><el-form-item :label='uiText("名称")'><el-input v-model="form.name"  :maxlength="entity==='budget'?255:128"/></el-form-item><el-form-item :label='uiText("原币（创建后固定，不自动换算）")'><el-select v-model="form.currency"><el-option v-for="c in ['CNY','MXN','USD','EUR']" :key="c" :value="c" :label="c"/></el-select></el-form-item><el-form-item :label='uiText("产品平台")'><el-select v-model="form.productLineId" filterable clearable><el-option v-for="p in options.productLines" :key="p.productId" :value="p.productId" :label="p.productName"/></el-select></el-form-item><el-form-item v-if="entity==='expense'||query.kind==='mold'" :label='uiText("款式＋兼容型号档案（不分颜色）")'><PhoneObjectIdSelect v-model="form.itemId" :options="itemObjects" type="item" :label='uiText("预算／费用模具档案")' @selected="itemChanged"/></el-form-item><el-form-item v-if="entity==='expense'||query.kind==='mold'" :label='uiText("明确模具套（可选）")'><el-select v-model="form.moldSetId" filterable clearable><el-option v-for="s in options.molds.filter(s=>s.itemId===form.itemId)" :key="s.moldSetId" :value="s.moldSetId" :label="`${s.setNo} · ${s.setName}`"/></el-select></el-form-item><el-form-item v-if="entity==='budget'&&query.kind==='mold'" :label='uiText("所属平台预算（同币种；父子不叠加）")'><el-select v-model="form.parentBudgetId" filterable clearable><el-option v-for="b in options.budgets.filter(b=>b.budgetKind==='platform'&&b.currencyCode===form.currency&&b.productLineId===form.productLineId)" :key="b.budgetId" :value="b.budgetId" :label="String(b.budgetName)"/></el-select></el-form-item></el-form><template #footer><el-button @click="createOpen=false">{{ uiText("取消") }}</el-button><el-button type="primary" :loading="loadingAction" @click="create">{{ uiText("创建") }}</el-button></template></el-dialog>
    <el-dialog v-model="saveOpen" :title='uiText("批量保存与修订说明")' width="720px"><p>{{ changedCount }}{{ uiText(" 个单元格。预算每次保存生成新版本；失败或冲突行仍保留草稿。") }}</p><el-input v-model="reason" type="textarea" :rows="4" :placeholder='uiText("预算修订/费用作废/ERP映射原因（按适用项必填）")'/><template #footer><el-button @click="saveOpen=false">{{ uiText("继续填写") }}</el-button><el-button type="primary" :loading="loadingAction" @click="save">{{ uiText("保存修改字段") }}</el-button></template></el-dialog>
    <el-drawer v-model="historyOpen" :title='uiText("修改历史与预算版本")' size="88%"><el-table :data="history" border><el-table-column prop="versionNo" :label='uiText("版本")' width="80"/><el-table-column prop="fieldName" :label='uiText("字段")' width="170"/><el-table-column prop="beforeJson" :label='uiText("原值")' min-width="220"/><el-table-column prop="afterJson" :label='uiText("新值")' min-width="220"/><el-table-column prop="reason" :label='uiText("原因")' min-width="230"/><el-table-column prop="actorName" :label='uiText("真实账号")' width="170"/><el-table-column prop="changedAt" :label='uiText("保存时间")' width="200"/></el-table><el-pagination v-model:current-page="historyPage" :total="historyTotal" :page-size="20" @current-change="loadHistory"/></el-drawer>
    <el-dialog v-model="conflictOpen" :title='uiText("同字段冲突：核对后重新保存")' width="90%"><section v-for="result in conflicts" :key="result.id"><h4>{{ uiText("记录 #") }}{{ result.id }}</h4><el-table :data="result.conflicts" border><el-table-column prop="field" :label='uiText("字段")'/><el-table-column prop="before" :label='uiText("原值")'/><el-table-column prop="current" :label='uiText("当前保存值")'/><el-table-column prop="attempted" :label='uiText("待保存值")'/></el-table><el-button @click="rebase(result)">{{ uiText("我已核对，采用当前值为新原值并保留草稿") }}</el-button></section></el-dialog>
    <FinanceImportDialog v-model="importOpen" :entity="entity" :options="options" @completed="refresh();emit('changed')"/>
  </main>
</template>
<style scoped>
.finance-ledger {
  height:100%; min-height:0; width:100%; min-width:0; box-sizing:border-box;
  padding:20px; display:flex; flex-direction:column; overflow:auto;
}
.finance-ledger>header,.finance-ledger>.el-tabs,.finance-ledger>.summary,
.finance-ledger>.filters,.finance-ledger>.el-alert,.finance-ledger>footer { flex:none; }
header { display:flex; justify-content:space-between; align-items:flex-start; gap:20px; }
h2 { margin:0 0 8px; font-size:23px; }
header p { margin:0; color:var(--el-text-color-secondary); max-width:1000px; line-height:1.5; }
.actions,.filters { display:flex; align-items:center; gap:10px; flex-wrap:wrap; }
.actions { justify-content:flex-end; }
.filters { margin:16px 0; }
.filters .search { width:340px; }
.filters .el-select { width:200px; }
.draft-count { margin-left:auto; color:var(--el-color-warning); }
.summary { display:flex; gap:12px; flex-wrap:wrap; margin:14px 0; }
.summary>div { background:var(--el-fill-color-light); padding:12px 16px; display:grid; gap:8px; min-width:260px; }
.wide-table { width:100%; min-height:0; }
.wide-table :deep(.el-table__cell) { padding:10px 0; }
.wide-table :deep(.el-input__wrapper) { min-height:38px; }
.cell-select { width:100%; min-height:38px; border:1px solid var(--el-border-color); border-radius:4px; color:var(--el-text-color-regular); background:var(--el-bg-color); padding:6px; }
.dirty :deep(.el-input__wrapper) { box-shadow:0 0 0 1px var(--el-color-warning) inset; background:#fff9e8; }
small { color:var(--el-text-color-secondary); font-size:12px; }
footer { display:flex; justify-content:space-between; align-items:center; margin-top:14px; gap:12px; }
.error { color:var(--el-color-danger); margin:6px 0; }
.create-grid { display:grid; grid-template-columns:1fr 1fr; gap:0 24px; }
.create-grid .el-select { width:100%; }
@media(max-width:900px) {
  header { flex-direction:column; }
  .actions { justify-content:flex-start; }
  .filters .search { width:100%; }
  .draft-count { margin-left:0; }
  footer { flex-direction:column; align-items:flex-start; }
  .create-grid { grid-template-columns:1fr; }
  .finance-ledger { padding:14px; }
}
</style>
