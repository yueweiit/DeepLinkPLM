<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { useResizeObserver } from '@vueuse/core'
import { moldBusinessStatusNames } from '@/api/modules/moldApplications'
import type { MoldApplication } from '@/api/modules/moldApplications'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MoreFilled } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import * as api from '@/api/modules/schedule'
import type { ScheduleOptions, ScheduleQuery, ScheduleRow, ScheduleTask } from '@/api/modules/schedule'
import type { CellValue, CollaborationRow, PlanField, PlanHistory } from '@/types/collaboration'
import { useCachedRequest } from '@/composables/useCachedRequest'
import { useUserStore } from '@/stores/user'
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace'
import { acceptanceNames, archivePlatformName, groupNames, isCollaborationEntry, moldScheduleTaskRoute, moldStatuses, parseCell, parseClipboard, rowKey, statusNames, validateMoldRow } from '@/utils/collaboration'
import FixedTableViewport from '@/components/FixedTableViewport/index.vue'
import CollaborationCell from '@/views/collaboration/components/CollaborationCell.vue'
import CollaborationImportDialog from '@/views/collaboration/components/CollaborationImportDialog.vue'
import MoldOverdueIndicator from '@/components/MoldOverdueIndicator/index.vue'
import {phoneMemberName} from '@/utils/phoneMembers'
import {moldHandlerNames} from '@/utils/moldApplicationHandlers'
import { moldDateClass, moldOverdueCell, moldSetPhones } from '@/utils/moldPresentation'
import MoldTaskList from './components/MoldTaskList.vue'
import ScheduleTaskEditor from './components/ScheduleTaskEditor.vue'
import MoldSheetSyncControls from './components/MoldSheetSyncControls.vue'
defineOptions({ name: 'MoldScheduleView' })
const user = useUserStore(), workspace = useCollaborationWorkspace(), router = useRouter(), route = useRoute(), drafts = workspace.drafts
const options = ref<ScheduleOptions>(), error = ref(''), keyword = ref(''), productLineId = ref<number>(), status = ref(''), mineOnly = ref(false), overdueOnly = ref(false), page = ref(1), size = ref(50), group = ref(''), layer = ref('overview'), expanded = ref(false), saving = ref(false)
const presentation = ref('table'), expandedParts = ref(new Set<number>())
const tabFromRoute=()=>route.query.tab==='tasks'?'tasks':route.query.tab==='applications'?'applications':'sets'
const canvas=ref<HTMLElement>(),containerWidth=ref(1500),scheduleTab=ref(tabFromRoute()),applicationStatus=ref('')
useResizeObserver(canvas,entries=>{containerWidth.value=entries[0]?.contentRect.width||1500})
const applicationRequest=useCachedRequest(api.getScheduleApplications),applications=computed(()=>applicationRequest.hasCurrentData.value?applicationRequest.data.value?.content||[]:[]),applicationTotal=computed(()=>applicationRequest.data.value?.totalElements||0)
const progressStatusOptions=computed(()=>Object.fromEntries(Object.entries(moldBusinessStatusNames).filter(([key])=>key!=='draft')))
const noteRow=ref<ScheduleRow>(),noteVisible=ref(false)
const notePreview=(row:ScheduleRow)=>String(value(row,'scheduleNotes')||'未填写').slice(0,38)
function expandNote(row:ScheduleRow){noteRow.value=row;noteVisible.value=true}
function applicationQuery():api.ScheduleApplicationQuery{return {projectId:Number(route.query.projectId)||undefined,page:page.value,size:size.value,keyword:keyword.value.trim(),status:applicationStatus.value||undefined,productLineId:productLineId.value,phoneModelId:Number(route.query.phoneModelId)||undefined}}
function loadApplications(force=false){return options.value&&user.token?applicationRequest.execute(applicationQuery(),force):Promise.resolve()}
function openApplication(id:number,historical=false){void router.push({path:'/mold-applications',query:{applicationId:id,...(historical?{review:'historical'}:{})}})}
function applicationStage(row:MoldApplication){
  if(row.historicalAssociation?.associationOnly)return '历史已关联'
  if(row.executionStatus==='paused')return '已暂停'
  if(row.executionStatus==='terminated')return '已终止执行'
  if(row.businessStatus!=='approved')return moldBusinessStatusNames[row.businessStatus||'approval_unknown']||'待核对'
  if(row.provisioningStatus==='blocked')return '建档待核对'
  if(row.provisioningStatus==='not_requested')return '待核对历史关联'
  if(row.provisioningStatus==='pending')return '正在建立模具资料'
  if(row.provisioningStatus==='ready')return row.scheduleStatus==='pending'?'待开工':row.scheduleStatus==='market_ready'?'已确认上市':statusNames[row.scheduleStatus||'']||'已进入实物排程'
  return '建档待核对'
}
function applicantPhone(row:MoldApplication){return row.standardPhone?phoneMemberName(row.standardPhone):row.model||'标准手机待关联'}
function applicantCode(row:MoldApplication){return row.standardPhone?row.standardPhone.phoneModelCode||'编码待分配':row.phoneModelCode||'编码待分配'}
function applicationMold(row:MoldApplication){if(row.moldSetId)void router.push({path:'/mold-schedules',query:{moldSetId:row.moldSetId}});else openApplication(row.applicationId)}
const request = useCachedRequest(api.getSchedulePage), rows = computed(() => request.hasCurrentData.value ? request.data.value?.content || [] : []), total = computed(() => request.data.value?.totalElements || 0)
const taskRequest=useCachedRequest(api.getScheduleTasks)
const tasks=computed(()=>taskRequest.hasCurrentData.value?taskRequest.data.value?.content||[]:[])
const taskTotal=computed(()=>taskRequest.data.value?.totalElements||0)
const taskCategory=ref<NonNullable<ScheduleQuery['category']>>('all'),taskMineOnly=ref(true)
const selectedTask=ref<ScheduleTask>(),taskDrawerVisible=ref(false),taskEditor=ref<InstanceType<typeof ScheduleTaskEditor>>()
const visitedTaskScopes=ref(new Map<number,Set<string>>())
const taskId=(task?:ScheduleTask)=>task?`${task.moldSetId}:${task.taskKey}`:''
const focusedMoldId=computed(()=>Number(route.query.moldSetId||route.query.selectedMoldSetId)||undefined)
let taskGeneration=0,focusedTaskStamp=''
function recordTaskScope(id:number,fields:string[]){const keys=visitedTaskScopes.value.get(id)||new Set<string>();fields.forEach(key=>keys.add(key));visitedTaskScopes.value.set(id,keys)}
function taskQuery():ScheduleQuery{return {...query(),mineOnly:taskMineOnly.value,category:taskCategory.value}}
async function focusTask(force=false){
  if(!options.value||!user.token||!taskRequest.hasCurrentData.value)return
  const stamp=`${focusedMoldId.value||''}:${route.query.taskKey||''}`
  if((!force&&stamp===focusedTaskStamp)||!focusedMoldId.value||!route.query.taskKey)return
  focusedTaskStamp=stamp
  if(!force&&taskId(selectedTask.value)===stamp)return
  const token=user.token,ticket=taskGeneration
  let target=tasks.value.find(t=>taskId(t)===stamp)
  if(!target){
    try{const result=await api.getScheduleTasks({...taskQuery(),page:1,size:1,moldSetId:focusedMoldId.value,taskKey:String(route.query.taskKey)});target=result.content[0]}catch{if(token===user.token&&ticket===taskGeneration&&stamp===focusedTaskStamp){error.value='指定待办加载失败，请刷新重试';focusedTaskStamp=''}return}
  }
  if(token!==user.token||ticket!==taskGeneration||stamp!==focusedTaskStamp||!active||scheduleTab.value!=='tasks')return
  selectedTask.value=target;taskDrawerVisible.value=!!target
}
async function loadTasks(force=false){
  if(!options.value||!user.token)return
  const ticket=++taskGeneration
  await taskRequest.execute(taskQuery(),force)
  if(ticket!==taskGeneration||!active||!taskRequest.hasCurrentData.value||taskRequest.error.value)return
  await focusTask(!!selectedTask.value&&!tasks.value.some(t=>taskId(t)===taskId(selectedTask.value)))
}
function selectTask(task:ScheduleTask){
  selectedTask.value=task;taskDrawerVisible.value=true
  void router.replace(moldScheduleTaskRoute({...route.query,selectedMoldSetId:task.moldSetId,taskKey:task.taskKey}))
}
function closeTask(){
  selectedTask.value=undefined;taskDrawerVisible.value=false
  const {taskKey,selectedMoldSetId,...query}=route.query
  if((taskKey||selectedMoldSetId)&&active&&user.token&&route.path==='/mold-schedules')void router.replace({path:'/mold-schedules',query:{...query,tab:scheduleTab.value}})
}
function openPendingTask(row:ScheduleRow,task:ScheduleTask){void router.push(moldScheduleTaskRoute({moldSetId:row.moldSetId,taskKey:task.taskKey}))}
async function taskHistory(row:ScheduleRow){await open(row);try{await loadHistory()}catch{error.value='修改历史加载失败，请重试'}}
const moldDrafts = computed(() => drafts.entries(scheduleScope.value)), dirtyCount = computed(() => moldDrafts.value.reduce((n, [, d]) => n + d.changes.size, 0))
const value = (row: CollaborationRow, key: string) => drafts.value(row, key), dirty = (row: CollaborationRow, key: string) => drafts.rows.get(rowKey(row))?.changes.has(key), can = (f: PlanField) => f.group!=='finance' && !!options.value?.writableGroups.includes(f.group) && user.hasPermission('schedule:write')
const canEdit=(row:CollaborationRow,f:PlanField)=>can(f)&&(!row.schedulePaused||!!options.value?.pausedWritableKeys?.includes(f.key))
const field = (key: string) => options.value?.moldFields.find(f => f.key === key)
const layerKeys: Record<string, string[]> = {
  overview: ['ownerUserId','openedDate','plannedMoldDeliveryDate','actualShipDate','plannedFactoryArrivalDate','actualMexicoArrivalDate','plannedAcceptanceDate','plannedProductionDate'],
  plan: ['planStartDate','openedDate','manufacturingDays','holidayDays','extraDays','ownerUserId','plannedMoldDeliveryDate','logisticsOwnerUserId','plannedFactoryArrivalDate','mexicoOwnerUserId','plannedAcceptanceDate','productionOwnerUserId','plannedProductionDate'],
  forecast: ['forecastDeliveryDate','forecastFactoryDate','forecastAcceptanceDate','forecastProductionDate','scheduleNotes'],
  actual: ['openedDate','actualSupplierTrialDate','actualShipDate','actualMexicoArrivalDate','actualPortArrivalDate','actualTrialDate','actualProductionDate','marketReadyDate']
}
const columnLabels: Record<string, string> = {
  planStartDate:'计划开模', openedDate:'开模日期', manufacturingDays:'制作天数', holidayDays:'假期天数', extraDays:'额外天数',
  ownerUserId:'模具负责人', plannedMoldDeliveryDate:'计划模具交付', logisticsOwnerUserId:'物流负责人', plannedFactoryArrivalDate:'计划到墨工厂',
  mexicoOwnerUserId:'验收负责人', plannedAcceptanceDate:'计划验收', productionOwnerUserId:'量产负责人', plannedProductionDate:'计划试模量产',
  forecastDeliveryDate:'预测模具交付', forecastFactoryDate:'预测到厂', forecastAcceptanceDate:'预测验收', forecastProductionDate:'预测量产',
  actualSupplierTrialDate:'中国实际试模', actualShipDate:'实际发模', actualMexicoArrivalDate:'实际抵墨', actualPortArrivalDate:'实际到港',
  actualTrialDate:'墨西哥试模', actualProductionDate:'实际量产', marketReadyDate:'上市确认'
}
const columnWidth = (f: PlanField) => f.kind === 'date' ? 110 : f.key.endsWith('UserId') ? 92 : f.kind === 'count' ? 70 : 260
const narrow=computed(()=>containerWidth.value<Math.max(1100,522+columns.value.reduce((sum,f)=>sum+columnWidth(f),0)))
const columns = computed(() => layerKeys[layer.value].map(field).filter((f): f is PlanField => !!f && (!group.value || f.group === group.value)))
const suggestionBasis = computed<Record<string, string>>(() => ({
  plannedMoldDeliveryDate:'开模日期（未填写时用计划开模）＋制作天数＋假期补偿＋额外天数，按自然日计算。',
  plannedFactoryArrivalDate:`模具交付日期＋${options.value?.transportDays ?? 7}天；有当前预测时优先使用预测交付日期。`,
  plannedAcceptanceDate:`到厂日期＋${options.value?.acceptanceDays ?? 1}天；有当前预测时优先使用预测到厂日期。`,
  plannedProductionDate:`验收日期＋${options.value?.productionDays ?? 3}天；有当前预测时优先使用预测验收日期。`
}))
const planKeys = ['planStartDate','openedDate','manufacturingDays','holidayDays','extraDays','plannedMoldDeliveryDate','plannedFactoryArrivalDate','plannedAcceptanceDate','plannedProductionDate']
const partColumns = computed(() => ['pc','tpu'].map(p => ({ part: p.toUpperCase(), fields: [p+'Status',p+'SignedDate',p+'FactoryArrivalDate',p+'AcceptanceStatus',p+'AcceptanceDate',p+'AcceptanceIssues'].map(field).filter((f): f is PlanField => !!f) })))
const scheduleScope=computed(()=>({entity:'mold_set' as const,fields:[...new Set([...Object.values(layerKeys).flat(),...partColumns.value.flatMap(p=>p.fields.map(f=>f.key))])]}))
const moduleDirtyCount=computed(()=>{
  const keys=new Set(moldDrafts.value.flatMap(([id,d])=>[...d.changes.keys()].map(field=>`${id}:${field}`)))
  visitedTaskScopes.value.forEach((fields,id)=>drafts.patches({entity:'mold_set',id,fields:[...fields]}).forEach(p=>p.changes.forEach(c=>keys.add(`mold_set:${id}:${c.field}`))))
  return keys.size
})
function clearModuleDrafts(){drafts.clear(scheduleScope.value);visitedTaskScopes.value.forEach((fields,id)=>drafts.clear({entity:'mold_set',id,fields:[...fields]}))}
const activeRequest=computed(()=>scheduleTab.value==='tasks'?taskRequest:scheduleTab.value==='applications'?applicationRequest:request)
interface TimelineStep { key: string; title: string; plan?: string; forecast?: string; actual: { key: string; label: string }[] }
// Presentation-only mappings: dates remain the shared saved fields or explicitly marked drafts.
const milestones: TimelineStep[] = [
  { key:'opening',title:'开模',plan:'planStartDate',actual:[{key:'openedDate',label:'实际开模'}] },
  { key:'delivery',title:'制模 / 发模',plan:'plannedMoldDeliveryDate',forecast:'forecastDeliveryDate',actual:[{key:'actualSupplierTrialDate',label:'中国制模试模'},{key:'actualShipDate',label:'实际发模'}] },
  { key:'factory',title:'运输 / 到厂',plan:'plannedFactoryArrivalDate',forecast:'forecastFactoryDate',actual:[{key:'actualMexicoArrivalDate',label:'实际抵达墨西哥'},{key:'actualPortArrivalDate',label:'实际到港'},{key:'pcFactoryArrivalDate',label:'PC 实际到厂'},{key:'tpuFactoryArrivalDate',label:'TPU 实际到厂'}] },
  { key:'acceptance',title:'验收',plan:'plannedAcceptanceDate',forecast:'forecastAcceptanceDate',actual:[{key:'pcAcceptanceDate',label:'PC 验收日期'},{key:'tpuAcceptanceDate',label:'TPU 验收日期'}] },
  { key:'production',title:'试模 / 量产',plan:'plannedProductionDate',forecast:'forecastProductionDate',actual:[{key:'actualTrialDate',label:'墨西哥实际试模'},{key:'actualProductionDate',label:'墨西哥实际量产'}] },
  { key:'market',title:'上市确认',actual:[{key:'marketReadyDate',label:'实际上市确认'}] }
]
function toggleParts(id: number) { if (expandedParts.value.has(id)) expandedParts.value.delete(id); else expandedParts.value.add(id) }
function partDisplay(row: CollaborationRow, f: PlanField) { const v=value(row,f.key); return f.kind==='acceptance'?acceptanceNames[String(v)] || '未填写':f.kind==='part_status'?({pending:'待制作',making:'制作中',ready:'已完成'} as Record<string,string>)[String(v)] || '未填写':display(v) }
function query(): ScheduleQuery { return { projectId:Number(route.query.projectId)||undefined,page: page.value, size: size.value, keyword: keyword.value.trim(), productLineId: productLineId.value, status: status.value || undefined, mineOnly: mineOnly.value, overdueOnly: overdueOnly.value, moldSetId: Number(route.query.moldSetId) || undefined, itemId:Number(route.query.itemId)||undefined } }
let timer: ReturnType<typeof setTimeout> | undefined, active = true, mounted = false, generation = 0
function load(force = false) { return scheduleTab.value==='tasks'?loadTasks(force):scheduleTab.value==='applications'?loadApplications(force):options.value && user.token ? request.execute(query(), force) : Promise.resolve() }
function reset(debounce = false) { clearTimeout(timer);taskGeneration++;request.invalidate();applicationRequest.invalidate();taskRequest.invalidate();page.value = 1;if (debounce) timer = setTimeout(() => void load(), 300); else void load() }
watch(keyword, () => reset(true), { flush: 'sync' }); watch([productLineId,status,mineOnly,overdueOnly,size,applicationStatus,taskCategory,taskMineOnly], () => reset(), { flush: 'sync' })
watch(() => JSON.stringify([route.query.projectId,route.query.moldSetId,route.query.itemId,route.query.productLineId,route.query.phoneModelId,route.query.tab]), () => {scheduleTab.value=tabFromRoute();productLineId.value=Number(route.query.productLineId)||undefined;reset()})
watch(()=>JSON.stringify([route.query.selectedMoldSetId,route.query.taskKey]),()=>{if(active&&scheduleTab.value==='tasks'){if(!route.query.taskKey){selectedTask.value=undefined;taskDrawerVisible.value=false;focusedTaskStamp=''}else void focusTask()}})
watch(scheduleTab,tab=>{page.value=1;if(tab!=='tasks')taskDrawerVisible.value=false;if(tab!==tabFromRoute())void router.replace({query:{...route.query,tab}});else void load()})
watch(() => workspace.revision, () => {request.clear();applicationRequest.clear();taskRequest.clear();if(active&&mounted)void Promise.all([load(),scheduleTab.value==='sets'?loadApplications():request.execute(query())])}, { flush: 'sync' })
watch(() => user.token, () => {generation++;taskGeneration++;clearTimeout(timer);[request,applicationRequest,taskRequest].forEach(r=>{r.clear();r.data.value=undefined;r.hasCurrentData.value=false});selectedTask.value=undefined;taskDrawerVisible.value=false;visitedTaskScopes.value.clear();focusedTaskStamp='';noteVisible.value=false;noteRow.value=undefined;options.value=undefined;detail.value=undefined;history.value=[];detailVisible.value=false;review.value=false;importVisible.value=false;expandedParts.value.clear()}, { flush: 'sync' })
function edit(row: CollaborationRow, f: PlanField, text: string, quiet = false) { if (!canEdit(row,f)) return; try { drafts.set(row, f.key, parseCell(f, text)) } catch (e) { if (!quiet) ElMessage.warning(e instanceof Error ? e.message : uiText('格式不正确')) } }
function paste(row: CollaborationRow, f: PlanField, event: ClipboardEvent) {
  const text = event.clipboardData?.getData('text/plain') || ''; if (!/[\t\n\r]/.test(text)) return; event.preventDefault()
  try { const cells = parseClipboard(text), start = rows.value.findIndex(r => r.moldSetId === row.moldSetId), col = columns.value.findIndex(c => c.key === f.key); const staged: { row: CollaborationRow; field: PlanField; value: CellValue }[] = []
    cells.forEach((r, ri) => r.forEach((t, ci) => { const target = rows.value[start + ri], targetField = columns.value[col + ci]; if (!target || !targetField || !canEdit(target,targetField)) throw new Error('粘贴超出本页或包含只读列'); staged.push({ row: target, field: targetField, value: parseCell(targetField, t) }) })); staged.forEach(c => drafts.set(c.row, c.field.key, c.value)); ElMessage.success(uiText('已暂存 {value0} 个单元格',{value0:(staged.length)})) } catch (e) { ElMessage.warning(e instanceof Error ? e.message : uiText('粘贴失败')) }
}
function suggestions(row: CollaborationRow) {
  const add = (v: CellValue, n: number): string | null => v ? dayjs(String(v)).add(n,'day').format('YYYY-MM-DD') : null
  const days = value(row,'manufacturingDays') ?? options.value?.styleDays[row.styleName], start = value(row,'openedDate') || value(row,'planStartDate')
  const delivery = days == null ? null : add(start,Number(days)+Number(value(row,'holidayDays') || 0)+Number(value(row,'extraDays') || 0))
  const factory = add(value(row,'forecastDeliveryDate') || value(row,'plannedMoldDeliveryDate') || delivery,options.value?.transportDays || 7)
  const acceptance = add(value(row,'forecastFactoryDate') || value(row,'plannedFactoryArrivalDate') || factory,options.value?.acceptanceDays || 1)
  const production = add(value(row,'forecastAcceptanceDate') || value(row,'plannedAcceptanceDate') || acceptance,options.value?.productionDays || 3)
  return { plannedMoldDeliveryDate: delivery, plannedFactoryArrivalDate: factory, plannedAcceptanceDate: acceptance, plannedProductionDate: production }
}
function adopt(row: CollaborationRow, key: string) { const f = field(key), date = suggestions(row)[key as keyof ReturnType<typeof suggestions>]; if (!f || !can(f) || !date || row.schedulePaused) return; if (value(row,key)) { ElMessage.warning(uiText('此计划已有确认日期，建议不会覆盖；请手工更正并填写原因')); return } drafts.set(row,key,date,true) }
const review = ref(false), detailVisible = ref(false), detail = ref<ScheduleRow>(), detailLoading = ref(false), history = ref<PlanHistory[]>([]), importVisible = ref(false)
async function save() {
  const patches = drafts.patches(scheduleScope.value); if (!patches.length) return
  for (const patch of patches) { const draft = drafts.rows.get(`mold_set:${patch.id}`)!; const message = validateMoldRow(draft.source,key => scheduleScope.value.fields.includes(key)?value(draft.source,key):(draft.source[key] as CellValue)??null); if (message) { error.value = message; review.value = true; return } }
  saving.value = true; error.value = ''; let failed = 0, saved = 0
  try { for (let n = 0; n < patches.length; n += 100) { const submitted = patches.slice(n,n+100), results = await api.saveScheduleRows(submitted); results.forEach(r => { drafts.apply(r,submitted.find(p => p.id === r.id)); if (r.status === 'saved') saved++; else failed++; if (detail.value?.moldSetId === r.id && r.row) detail.value = r.row as ScheduleRow }) }
    if (saved) workspace.changed(); else request.clear(); await load(); if (failed) { error.value = `${failed} 行未保存，草稿和冲突已保留`; review.value = true } else { review.value = false; ElMessage.success(uiText('已保存 {value0} 套排程',{value0:(saved)})) }
  } catch { error.value = '保存未确认完成，草稿已保留' } finally { saving.value = false }
}
async function discard() { try { await ElMessageBox.confirm(uiText('仅撤销开模排程字段草稿，其他模块字段保留。'),uiText('撤销修改')); drafts.clear(scheduleScope.value); error.value = '' } catch {} }
async function reason(row: CollaborationRow) { const d = drafts.rows.get(rowKey(row)); if (!d) return; try { const result = await ElMessageBox.prompt(uiText('日期更正、额外周期和人工核对原因'),uiText('更正说明'),{ inputType:'textarea',inputValue:d.reason }); d.reason = result.value.trim() } catch {} }
async function open(row: ScheduleRow) { const ticket = ++generation; detailVisible.value = true; detailLoading.value = true; history.value = []; try { const result = await api.getSchedule(row.moldSetId!); if (ticket === generation) detail.value = result } catch { error.value = '详情加载失败' } finally { if (ticket === generation) detailLoading.value = false } }
async function loadHistory() { if (!detail.value) return; const ticket = generation, result = await api.getScheduleHistory(detail.value.moldSetId!); if (ticket === generation) history.value = result.content }
async function baseline(row: ScheduleRow) { if(row.schedulePaused){ElMessage.warning(uiText('申请执行已暂停，请先处理申请'));return} if (drafts.rows.has(rowKey(row))) { ElMessage.warning(uiText('请先保存或撤销本套草稿，再确认基准')); return } try { const result = await ElMessageBox.prompt(uiText('记录本次计划版本的确认依据，不覆盖以前的基准。'),uiText('留存计划基准'),{inputType:'textarea',inputValidator:v => !!v?.trim() || uiText('请填写确认依据')}); await api.confirmSchedulePlan(row.moldSetId!,Object.fromEntries(planKeys.map(k => [k,(row[k] as CellValue) ?? null])),result.value); workspace.changed(); await load(); ElMessage.success(uiText('已留存不可变计划基准')) } catch (e) { if (e instanceof Error) error.value = e.message } }
async function refresh() { error.value = '';focusedTaskStamp='';await load(true);if(taskDrawerVisible.value)await taskEditor.value?.refresh() }
async function remind() { const result = await api.enqueueScheduleReminders(); ElMessage.success(uiText('新增 {value0} 条站内提醒，{value1} 项未指定负责人；未发送外部消息',{value0:(result.created),value1:(result.unassigned)})) }
function openImport() { if (dirtyCount.value) { ElMessage.warning(uiText('请先保存或撤销模具草稿，再导入预览')); return } importVisible.value = true }
function rowAction(command: string, row: ScheduleRow) {
  const actions: Record<string, (row: ScheduleRow) => unknown> = { detail: open, baseline, reason }
  void actions[command]?.(row)
}
function unloaded(event: BeforeUnloadEvent) { if (moduleDirtyCount.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(async to => { if (!moduleDirtyCount.value) return true; try { const shared = isCollaborationEntry(to.path); await ElMessageBox.confirm(shared?uiText('未保存草稿会在市场、产量、任务、台账与排程之间保留。'):uiText('还有未保存草稿，离开前确认撤销。'),uiText('未保存修改'),{confirmButtonText:shared?uiText('保留并前往'):uiText('撤销并离开'),cancelButtonText:uiText('继续填写')}); if (!shared) clearModuleDrafts(); return true } catch { return false } })
onMounted(async () => { window.addEventListener('beforeunload',unloaded); const token = user.token; try { const result = await api.getScheduleOptions(); if (token !== user.token) return; options.value = result; productLineId.value=Number(route.query.productLineId)||undefined; await Promise.all([load(),scheduleTab.value==='sets'?loadApplications():request.execute(query())]); mounted = true } catch { error.value = '排程初始化失败，请检查登录权限并刷新' } })
onActivated(() => { active = true; if (mounted) void load() }); onDeactivated(() => {active=false;taskGeneration++;focusedTaskStamp='';taskDrawerVisible.value=false;selectedTask.value=undefined;clearTimeout(timer);request.invalidate();taskRequest.invalidate();applicationRequest.invalidate()}); onBeforeUnmount(() => {generation++;taskGeneration++;clearTimeout(timer);window.removeEventListener('beforeunload',unloaded)})
const display = (v: unknown) => v == null || v === '' ? '未填写' : String(v)
</script>

<template>
  <main ref="canvas" class="mold-schedule compact-ledger" :class="{'wide-mode':expanded,'is-narrow':narrow,'is-small':containerWidth<720}">
    <header class="schedule-heading">
      <h2>{{ uiText("开模时间管控") }}</h2>
      <el-radio-group v-if="scheduleTab==='sets'" v-model="presentation" size="small" :aria-label='uiText("排程展示方式")'><el-radio-button value="table" data-test="schedule-table-mode">{{ uiText("表格") }}</el-radio-button><el-radio-button value="timeline" data-test="schedule-timeline-mode">{{ uiText("时间轴") }}</el-radio-button></el-radio-group>
      <el-radio-group v-if="scheduleTab==='sets' && presentation==='table'" v-model="layer" size="small" :aria-label='uiText("排程字段视图")'><el-radio-button value="overview" data-test="schedule-overview-layer">{{ uiText("关键日期总览") }}</el-radio-button><el-radio-button value="plan" data-test="schedule-plan-layer">{{ uiText("确认计划") }}</el-radio-button><el-radio-button value="forecast" data-test="schedule-forecast-layer">{{ uiText("当前预测") }}</el-radio-button><el-radio-button value="actual" data-test="schedule-actual-layer">{{ uiText("实际进度") }}</el-radio-button></el-radio-group>
      <el-radio-group v-model="scheduleTab" size="small" class="schedule-tabs" :aria-label='uiText("开模进度范围")'><el-radio-button value="sets" data-test="schedule-sets-tab">{{ uiText("实物排程") }}<span v-if="scheduleTab==='sets'"> · {{total}}{{ uiText(" 套") }}</span></el-radio-button><el-radio-button value="applications" data-test="schedule-applications-tab">{{ uiText("申请进度") }}<span v-if="scheduleTab==='applications' && applicationRequest.hasCurrentData.value"> · {{applicationTotal}}{{ uiText(" 份") }}</span></el-radio-button><el-radio-button value="tasks" data-test="schedule-tasks-tab">{{ uiText("待办处理") }}<span v-if="scheduleTab==='tasks' && taskRequest.hasCurrentData.value"> · {{taskTotal}}{{ uiText(" 项") }}</span></el-radio-button></el-radio-group>
      <div class="actions heading-actions">
        <MoldSheetSyncControls v-if="options?.administrator && user.hasPermission('schedule:write')" :dirty="moduleDirtyCount>0" :fields="options.moldFields" @updated="refresh"/>
        <el-popover placement="bottom-end" trigger="click" :width="370"><template #reference><el-button text size="small" :aria-label='uiText("排程填写说明")'>{{ uiText("填写说明") }}</el-button></template><div class="schedule-help"><p>{{ uiText("一行一套 PC＋TPU，点击单元格填写；支持 Excel 区域粘贴，修改后批量保存。黄色表示待保存，刷新不覆盖草稿。") }}</p><p>{{ uiText("制作周期按款式 12 / 13 / 14 自然日，加人工确认的假期与额外天数；运输 ") }}{{ options?.transportDays ?? 7 }}{{ uiText("、验收 ") }}{{ options?.acceptanceDays ?? 1 }}{{ uiText("、试模量产 ") }}{{ options?.productionDays ?? 3 }}{{ uiText(" 天仅作建议。点击日期旁的小标记查看依据并确认采用。") }}</p><p>{{ uiText("抵墨、到港、签收、到厂和验收分别记录；PC／TPU 均到厂且验收通过才完成交付。研发归档不终止交付任务。") }}</p></div></el-popover>
        <el-button size="small" @click="expanded=!expanded">{{ expanded?uiText('退出宽屏'):uiText('宽屏录入') }}</el-button><el-button size="small" @click="refresh">{{ uiText("刷新") }}</el-button><el-button size="small" @click="router.push('/molds')">{{ uiText("模具台账") }}</el-button>
        <el-dropdown trigger="click"><el-button size="small" :aria-label='uiText("排程更多操作")'>{{ uiText("更多") }}</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item @click="api.downloadScheduleTemplate(query())">{{ uiText("下载本页模板（含 ID）") }}</el-dropdown-item><el-dropdown-item @click="openImport">{{ uiText("Excel 导入预览") }}</el-dropdown-item><el-dropdown-item v-if="options?.administrator" @click="remind">{{ uiText("生成逾期站内提醒") }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
      </div>
    </header>
    <section class="schedule-panel">
      <div class="schedule-filters ledger-filters">
        <el-input v-model="keyword" size="small" clearable :placeholder='uiText("搜索实物编号 / 款式 / 兼容型号 / 厂家")' />
        <el-select v-model="productLineId" size="small" clearable :placeholder='uiText("全部产品线")'><el-option v-for="l in options?.productLines" :key="l.productLineId" :value="l.productLineId" :label="l.productName" /></el-select>
        <el-select v-if="scheduleTab!=='applications'" v-model="status" size="small" clearable :placeholder='uiText("全部进度")'><el-option v-for="key in moldStatuses" :key="key" :value="key" :label="statusNames[key]" /><el-option v-if="scheduleTab==='sets'" value="market_ready" :label='uiText("已确认上市")' /></el-select>
        <el-select v-if="scheduleTab==='sets' && presentation==='table'" v-model="group" size="small" clearable :placeholder='uiText("全部职责")'><el-option v-for="g in ['engineering','logistics','mexico']" :key="g" :value="g" :label="groupNames[g as keyof typeof groupNames]" /></el-select>
        <el-select v-if="scheduleTab==='applications'" v-model="applicationStatus" clearable size="small" :placeholder='uiText("全部申请状态")'><el-option v-for="(label,key) in progressStatusOptions" :key="key" :value="key" :label="label"/></el-select>
        <el-select v-if="scheduleTab==='tasks'" v-model="taskCategory" size="small" :aria-label='uiText("待办类别")'><el-option value="all" :label='uiText("全部待办类别")' /><el-option value="milestone" :label='uiText("进度节点")' /><el-option value="missing" :label='uiText("资料补填")' /><el-option value="review" :label='uiText("计划核对")' /></el-select>
        <el-checkbox v-if="scheduleTab==='sets'" v-model="mineOnly" size="small">{{ uiText("待我填写") }}</el-checkbox>
        <template v-if="scheduleTab==='tasks'"><el-checkbox v-if="options?.administrator" v-model="taskMineOnly" size="small">{{ uiText("仅本人待办") }}</el-checkbox><span v-else class="hint">{{ uiText("本人及职责分配任务") }}</span></template>
        <el-checkbox v-if="scheduleTab!=='applications'" v-model="overdueOnly" size="small">{{ uiText("仅逾期") }}</el-checkbox>
        <span class="record-count">{{scheduleTab==='tasks'?uiText('共 {value0} 项',{value0:(taskTotal)}):scheduleTab==='sets'?uiText('共 {value0} 套',{value0:(total)}):uiText('共 {value0} 份',{value0:(applicationTotal)})}}</span>
      </div>
      <el-alert v-if="error || activeRequest.error.value" :title="error || activeRequest.error.value" type="error" :closable="false" />
      <template v-if="scheduleTab==='tasks'">
        <div v-if="focusedMoldId && !selectedTask && !taskRequest.loading.value" class="task-location-note"><span>{{ uiText("本套暂无待办或指定任务已完成、状态已变化；不会自动打开其他任务。") }}</span><el-button size="small" @click="router.push({path:'/mold-schedules',query:{moldSetId:focusedMoldId,tab:'sets'}})">{{ uiText("打开本套排程") }}</el-button></div>
        <FixedTableViewport fill><template #default="{tableHeight}"><MoldTaskList :tasks="tasks" :loading="taskRequest.loading.value" :height="Math.max(100,tableHeight-40)" :selected="taskId(selectedTask)" :total="taskTotal" :page="page" :size="size" @select="selectTask" @page="page=$event;load()" @size="size=$event" /></template></FixedTableViewport>
      </template>
      <section v-else-if="scheduleTab==='applications'" class="application-list" :class="{'application-narrow':containerWidth<1260}" v-loading="applicationRequest.loading.value" :aria-label='uiText("开模申请进度")'>
        <div v-if="containerWidth>=1260" class="application-grid application-head"><span>{{ uiText("申请 / 手机型号") }}</span><span>{{ uiText("产品款式 / 模型") }}</span><span>{{ uiText("申请人／当前处理") }}</span><span>{{ uiText("审批状态") }}</span><span>{{ uiText("同意依据") }}</span><span>{{ uiText("当前进度") }}</span><span>{{ uiText("交期／下一步") }}</span><span>{{ uiText("操作") }}</span></div>
        <article v-for="row in applications" :key="row.applicationId" class="application-grid application-row" :data-application-id="row.applicationId">
          <div data-label="申请 / 手机型号"><button class="asset-button" @click="openApplication(row.applicationId)">{{row.approvalNo||uiText('草稿 #{value0}',{value0:(row.applicationId)})}}</button><span>{{applicantPhone(row)}}</span></div>
          <div data-label="产品款式 / 模型">{{row.tipo||uiText('款式待补')}}<small>{{applicantCode(row)}}</small></div>
          <div data-label="申请人／当前处理">{{row.applicantName||row.originatorName||uiText('申请人待核对')}}<small>{{moldHandlerNames(row)}}</small></div><div data-label="审批状态">{{moldBusinessStatusNames[row.businessStatus||'approval_unknown']||uiText('待核对')}}</div>
          <div data-label="同意依据"><template v-if="row.authorization">{{row.authorization.approverName}}<small>{{row.authorization.approvedAt||uiText('时间待核对')}}</small></template><span v-else>{{ uiText("尚无同意记录") }}</span></div>
          <div data-label="当前进度" :class="{warning:row.executionStatus==='paused'||row.provisioningStatus==='blocked'}">{{applicationStage(row)}}<small v-if="row.assetNumber">{{row.assetNumber}}</small><small v-if="row.provisioningError">{{row.provisioningError}}</small></div>
          <div data-label="交期／下一步">{{row.expectedDeliveryDate||row.form.expectedDeliveryDate||uiText('交期待补')}}<small>{{row.nextStep||applicationStage(row)}}</small></div><div data-label="操作" class="actions"><el-button size="small" link @click="openApplication(row.applicationId,row.sourceSystem==='dingtalk'&&!row.historicalAssociation)">{{row.sourceSystem==='dingtalk'&&!row.historicalAssociation?uiText('核对关联'):uiText('申请')}}</el-button><el-button v-if="row.itemId" size="small" link @click="router.push({path:'/molds',query:{itemId:row.itemId}})">{{ uiText("模具") }}</el-button><el-button v-if="row.moldSetId" size="small" link @click="applicationMold(row)">{{ uiText("排程") }}</el-button></div>
        </article><div v-if="!applications.length&&!applicationRequest.loading.value" class="schedule-empty">{{ uiText("暂无匹配申请。提交后的审批与执行进度在这里持续追踪；草稿在开模申请中完善。") }}</div>
      </section>
      <FixedTableViewport v-else-if="presentation==='table' && !narrow" v-slot="{tableHeight}" fill :refresh-key="rows"><el-table :data="rows" :height="tableHeight" v-loading="request.loading.value" row-key="moldSetId" :cell-class-name="moldOverdueCell" border class="schedule-table compact-table">
        <el-table-column fixed="left" type="expand" width="32"><template #default="{row}"><div class="schedule-parts"><section v-for="p in partColumns" :key="p.part"><h3>{{ p.part }}{{ uiText(" 到厂 / 验收") }}</h3><div v-for="f in p.fields" :key="f.key" class="part-entry"><label>{{ uiText(String((f.label) ?? '')) }}</label><CollaborationCell :field="f" :value="value(row,f.key)" :editable="canEdit(row,f)" :dirty="dirty(row,f.key)" @preview="edit(row,f,$event,true)" @edit="edit(row,f,$event)" /></div></section></div></template></el-table-column>
        <el-table-column fixed="left" :label='uiText("款式")' width="75"><template #default="{row}"><strong>{{ archivePlatformName(row) }}</strong></template></el-table-column>
        <el-table-column fixed="left" :label='uiText("本套适用手机")' min-width="160"><template #default="{row}">{{moldSetPhones(row)}}</template></el-table-column>
        <el-table-column fixed="left" :label='uiText("实物编号 / 套次")' width="105"><template #default="{row}"><button class="asset-button" :title="uiText('{value0} · 第 {value1} 套{value2}',{value0:(row.assetNumber),value1:(row.setNo || 1),value2:(drafts.rows.has(rowKey(row))?' · 待保存':'')})" @click="open(row)">{{ row.assetNumber }} · {{ row.setNo || 1 }}<span v-if="drafts.rows.has(rowKey(row))" class="row-dirty" :aria-label='uiText("本套有待保存修改")' /></button></template></el-table-column>
        <el-table-column v-for="f in columns" :key="f.key" :prop="f.key" :class-name="moldDateClass(f.key)" :label-class-name="moldDateClass(f.key)" :label="columnLabels[f.key] || f.label" :width="columnWidth(f)"><template #header><span :title="uiText(f.label)">{{ uiText(String((columnLabels[f.key] || f.label) ?? '')) }}</span></template><template #default="{row}">
          <div class="schedule-cell ledger-cell" :title="value(row,f.key) == null ? '' : String(value(row,f.key))">
            <button v-if="f.key==='scheduleNotes'" class="schedule-note-preview" @click="expandNote(row)">{{notePreview(row)}}<span>{{ uiText("展开") }}{{canEdit(row,f)?uiText(' / 编辑'):''}}</span></button><CollaborationCell v-else :field="f" :value="value(row,f.key)" :editable="canEdit(row,f)" :users="options?.users" :dirty="dirty(row,f.key)" compact @preview="edit(row,f,$event,true)" @edit="edit(row,f,$event)" @paste="paste(row,f,$event)" />
            <el-popover v-if="suggestionBasis[f.key]" placement="bottom" trigger="click" :width="320"><template #reference><button class="hint-trigger" :aria-label="uiText('查看{value0}建议',{value0:(f.label)})" :title="uiText('建议 {value0}',{value0:(display(suggestions(row)[f.key as keyof ReturnType<typeof suggestions>]))})">·</button></template><div class="schedule-help" :data-suggestion-field="f.key" :data-mold-set-id="row.moldSetId"><strong>{{ uiText(String((f.label) ?? '')) }}</strong><p>{{ uiText("建议 ") }}{{ display(suggestions(row)[f.key as keyof ReturnType<typeof suggestions>]) }}</p><p>{{ suggestionBasis[f.key] }}</p><el-button v-if="canEdit(row,f) && !value(row,f.key) && suggestions(row)[f.key as keyof ReturnType<typeof suggestions>]" size="small" type="primary" @click="adopt(row,f.key)">{{ uiText("确认采用") }}</el-button><p v-else-if="value(row,f.key)">{{ uiText("已有计划日期；调整请手工更正并填写原因，建议不会覆盖。") }}</p></div></el-popover>
            <el-popover v-else-if="f.key==='manufacturingDays' && value(row,f.key) == null" placement="bottom" trigger="click" :width="240"><template #reference><button class="hint-trigger" :aria-label='uiText("查看款式制作周期")'>·</button></template><div class="schedule-help">{{ uiText("款式默认 ") }}{{ options?.styleDays[row.styleName] ?? uiText('待人工确认') }}{{ uiText(" 天；填写后用于建议计算。") }}</div></el-popover>
          </div>
        </template></el-table-column>
        <el-table-column :label='uiText("交付状态")' width="100"><template #default="{row}"><div class="status-cell" :title="uiText('基准 V{value0} · {value1}',{value0:(row.planVersion || 0),value1:(row.marketEligible?'两部件验收通过，可确认上市':'尚未满足上市验收条件')})"><el-tag v-if="row.schedulePaused" size="small" type="warning">{{ uiText("已暂停") }}</el-tag><el-tag v-else-if="row.applicationPlanned" size="small">{{ uiText("待开工") }}</el-tag><el-tag v-else size="small">{{ statusNames[row.scheduleStatus] || (row.scheduleStatus==='market_ready'?uiText('已确认上市'):row.scheduleStatus) }}</el-tag><span v-if="row.baselineNeedsReview" class="warning review-marker" :title='uiText("计划变化，需核对基准")' :aria-label='uiText("计划变化，需核对")'>!</span></div></template></el-table-column>
        <el-table-column fixed="right" :label='uiText("延期")' width="60" align="center"><template #default="{row}"><MoldOverdueIndicator :row="row" /></template></el-table-column>
        <el-table-column fixed="right" :label='uiText("操作")' width="48" align="center"><template #default="{row}"><el-dropdown trigger="click" @command="rowAction($event,row)"><el-button text size="small" :icon="MoreFilled" :aria-label="uiText('操作 {value0}',{value0:(row.assetNumber)})" /><template #dropdown><el-dropdown-menu><el-dropdown-item command="detail">{{ uiText("详情 / 历史") }}</el-dropdown-item><el-dropdown-item v-if="options?.writableGroups.includes('engineering')&&!row.schedulePaused" command="baseline">{{ uiText("留存计划基准") }}</el-dropdown-item><el-dropdown-item v-if="drafts.rows.has(rowKey(row))" command="reason">{{ uiText("更正原因") }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></template></el-table-column>
        <template #empty><div class="schedule-empty">{{ uiText("暂无已确认的实物套。请在模具台账建套后排程；原表仅预览，不自动生成模具、颜色项目或库存。") }}</div></template>
      </el-table></FixedTableViewport>
      <section v-else-if="presentation==='table'" class="schedule-records" v-loading="request.loading.value" :aria-label='uiText("紧凑实物排程记录")'>
        <article v-for="row in rows" :key="row.moldSetId" class="schedule-record" :data-mold-set-id="row.moldSetId"><header><button class="asset-button" @click="open(row)">{{archivePlatformName(row)}} · {{row.assetNumber}}{{ uiText(" · 第 ") }}{{row.setNo||1}}{{ uiText(" 套") }}</button><span>{{row.schedulePaused?uiText('已暂停'):row.applicationPlanned?uiText('待开工'):statusNames[row.scheduleStatus]||row.scheduleStatus}}</span><MoldOverdueIndicator :row="row"/><el-button size="small" link @click="open(row)">{{ uiText("详情 / 历史") }}</el-button></header><p>{{moldSetPhones(row)}}</p>
          <div class="record-fields"><label v-for="f in columns" :key="f.key" :class="{'record-note':f.key==='scheduleNotes'}"><span>{{uiText(String((columnLabels[f.key]||f.label) ?? ''))}}</span><button v-if="f.key==='scheduleNotes'" class="schedule-note-preview" @click="expandNote(row)">{{notePreview(row)}}<span>{{ uiText("展开") }}{{canEdit(row,f)?uiText(' / 编辑'):''}}</span></button><CollaborationCell v-else :field="f" :value="value(row,f.key)" :editable="canEdit(row,f)" :users="options?.users" :dirty="dirty(row,f.key)" @preview="edit(row,f,$event,true)" @edit="edit(row,f,$event)" @paste="paste(row,f,$event)"/><el-button v-if="suggestionBasis[f.key]&&canEdit(row,f)&&!value(row,f.key)&&suggestions(row)[f.key as keyof ReturnType<typeof suggestions>]" size="small" text :title="suggestionBasis[f.key]" @click="adopt(row,f.key)">{{ uiText("采用建议 ") }}{{suggestions(row)[f.key as keyof ReturnType<typeof suggestions>]}}</el-button></label></div>
          <div class="actions"><el-button size="small" text @click="toggleParts(row.moldSetId!)">{{expandedParts.has(row.moldSetId!)?uiText('收起'):uiText('展开')}}{{ uiText(" PC / TPU 到厂与验收") }}</el-button><el-button v-if="options?.writableGroups.includes('engineering')&&!row.schedulePaused" size="small" text @click="baseline(row)">{{ uiText("留存计划基准") }}</el-button><el-button v-if="drafts.rows.has(rowKey(row))" size="small" text @click="reason(row)">{{ uiText("更正原因") }}</el-button></div>
          <div v-if="expandedParts.has(row.moldSetId!)" class="timeline-parts"><section v-for="p in partColumns" :key="p.part"><h3>{{p.part}}{{ uiText(" 到厂 / 验收") }}</h3><div v-for="f in p.fields" :key="f.key" class="part-entry"><label>{{uiText(String((f.label) ?? ''))}}</label><CollaborationCell :field="f" :value="value(row,f.key)" :editable="canEdit(row,f)" :dirty="dirty(row,f.key)" @preview="edit(row,f,$event,true)" @edit="edit(row,f,$event)"/></div></section></div>
        </article><div v-if="!rows.length&&!request.loading.value" class="schedule-empty">{{ uiText("暂无匹配实物套。申请审批进度请查看申请进度。") }}</div>
      </section>
      <section v-else class="schedule-timeline" v-loading="request.loading.value" :aria-label='uiText("本页模具排程时间轴")'>
        <p class="timeline-note">{{ uiText("已确认计划 → 当前预测 → 实际记录；建议日期不进入时间轴。待保存日期仅为草稿，不代表已确认或完成；抵墨、到港不等同于到厂。") }}</p>
        <article v-for="row in rows" :key="row.moldSetId" class="timeline-card" :data-mold-set-id="row.moldSetId">
          <header class="timeline-heading"><div><strong>{{ archivePlatformName(row) }} · {{ moldSetPhones(row) }}</strong><p>{{ row.assetNumber }}{{ uiText(" · 第 ") }}{{ display(row.setNo) }}{{ uiText(" 套 · 已保存基准 V") }}{{ row.planVersion || 0 }}</p></div><div class="actions"><el-tag v-if="row.schedulePaused" type="warning">{{ uiText("已暂停") }}</el-tag><el-tag v-else>{{ statusNames[row.scheduleStatus] || (row.scheduleStatus==='market_ready'?uiText('已确认上市'):row.scheduleStatus) }}</el-tag><el-tag v-if="drafts.rows.has(rowKey(row))" type="warning">{{ uiText("含待保存草稿") }}</el-tag><el-button link @click="open(row)">{{ uiText("详情 / 历史") }}</el-button></div></header>
          <div class="timeline-track"><section v-for="(step,index) in milestones" :key="step.key" class="timeline-step" :data-step="step.key"><h3><span>{{ index+1 }}</span>{{ uiText(String((step.title) ?? '')) }}</h3>
            <div class="timeline-layer confirmed" data-layer="plan"><label>{{ uiText("已确认计划") }}</label><time>{{ step.plan ? display(value(row,step.plan)) : uiText('未填写') }}</time><small v-if="step.plan && dirty(row,step.plan)" class="warning">{{ uiText("待保存，尚未确认") }}</small></div>
            <div class="timeline-layer forecast" data-layer="forecast"><label>{{ uiText("当前预测") }}</label><time>{{ step.forecast ? display(value(row,step.forecast)) : uiText('未填写') }}</time><small v-if="step.forecast && dirty(row,step.forecast)" class="warning">{{ uiText("待保存") }}</small></div>
            <div class="timeline-layer actual" data-layer="actual"><label>{{ uiText("实际记录") }}</label><dl v-for="entry in step.actual" :key="entry.key" :data-field="entry.key"><dt>{{ uiText(String((field(entry.key)?.label || entry.label) ?? '')) }}</dt><dd>{{ display(value(row,entry.key)) }}<small v-if="dirty(row,entry.key)" class="warning">{{ uiText(" · 待保存，非完成事实") }}</small></dd></dl></div>
          </section></div>
          <div class="timeline-footer"><span v-if="row.baselineNeedsReview" class="warning">{{ uiText("计划变化，需核对基准") }}</span><el-button text :aria-expanded="expandedParts.has(row.moldSetId!)" data-test="timeline-parts-toggle" @click="toggleParts(row.moldSetId!)">{{ expandedParts.has(row.moldSetId!)?uiText('收起'):uiText('展开') }}{{ uiText(" PC / TPU 到厂与验收") }}</el-button><small>{{ uiText("两部件独立记录；整套进度沿用已保存状态，不凭时间轴推算。") }}</small></div>
          <div v-if="expandedParts.has(row.moldSetId!)" class="timeline-parts"><section v-for="p in partColumns" :key="p.part" :data-part="p.part"><h3>{{ p.part }}{{ uiText(" 到厂 / 验收") }}</h3><div v-for="f in p.fields" :key="f.key" class="part-entry"><label>{{ uiText(String((f.label) ?? '')) }}</label><CollaborationCell v-if="canEdit(row,f)" :field="f" :value="value(row,f.key)" :editable="true" :dirty="dirty(row,f.key)" @preview="edit(row,f,$event,true)" @edit="edit(row,f,$event)" /><span v-else>{{ partDisplay(row,f) }}</span></div></section></div>
          <div v-if="row.tasks?.length" class="timeline-tasks"><strong>{{ uiText("已保存计划待办") }}</strong><button v-for="task in row.tasks" :key="task.taskKey" :class="{warning:task.overdue}" @click="openPendingTask(row,task)">{{ task.title }} · {{ display(task.dueDate) }}{{ task.overdue?uiText(' · 已逾期'):'' }}</button></div>
        </article>
        <div v-if="!rows.length" class="schedule-empty">{{ uiText("暂无已确认的实物套。请在模具台账建套后排程。") }}</div>
      </section>
      <el-pagination v-if="scheduleTab!=='tasks'" v-model:current-page="page" v-model:page-size="size" size="small" :page-sizes="[20,50,100]" :total="scheduleTab==='sets'?total:applicationTotal" layout="total,sizes,prev,pager,next" @current-change="load()" />
    </section>
    <footer v-if="dirtyCount && scheduleTab==='sets'" class="schedule-save"><strong>{{ moldDrafts.length }}{{ uiText(" 套 · ") }}{{ dirtyCount }}{{ uiText(" 个字段待保存") }}</strong><small>{{ uiText("共用草稿，刷新保留") }}</small><div class="actions"><el-button size="small" @click="review=true">{{ uiText("核对草稿 / 冲突") }}</el-button><el-button size="small" @click="discard">{{ uiText("撤销未保存修改") }}</el-button><el-button size="small" type="primary" :loading="saving" data-test="save-schedules" @click="save">{{ uiText("批量保存") }}</el-button></div></footer>
    <el-drawer v-model="detailVisible" :title='uiText("排程详情与计划基准")' size="96%"><div v-if="detail" v-loading="detailLoading" class="schedule-detail"><h2>{{ archivePlatformName(detail) }} · {{ moldSetPhones(detail) }}</h2><p v-if="!(Array.isArray(detail.compatiblePhones)&&detail.compatiblePhones.length)&&!detail.applicationLinks?.some(a=>a.standardPhone)" class="hint historical-phone-help">{{ uiText("“待核对”中的完整型号来自原资料，尚未确认本套适用范围；请在模具详情核对标准手机及 PC／TPU 适配。") }}</p><el-alert v-if="detail.schedulePaused" type="warning" :closable="false" :title='uiText("申请执行已暂停；计划与预测保留。可补录已有实际日期，并填写发生依据与原因。")'/><div v-if="detail.applicationLinks?.length" class="application-backlinks"><el-button v-for="a in detail.applicationLinks" :key="a.applicationId" size="small" @click="openApplication(a.applicationId)">{{ uiText("查看申请 ") }}{{a.approvalNo||`#${a.applicationId}`}}{{a.suspended?uiText(' · 已暂停'):''}}</el-button></div><p>{{ detail.assetNumber }}{{ uiText(" · 日期与模具台账共用；上市确认不会自动发布 BOM。") }}</p><div class="baseline-grid"><section><h3>{{ uiText("已确认基准 V") }}{{ detail.planVersion }}</h3><dl v-for="key in planKeys" :key="key"><dt>{{ uiText(String((field(key)?.label) ?? '')) }}</dt><dd>{{ display(detail.baseline?.[key]) }}</dd></dl></section><section><h3>{{ uiText("当前计划") }}</h3><dl v-for="key in planKeys" :key="key"><dt>{{ uiText(String((field(key)?.label) ?? '')) }}</dt><dd>{{ display(value(detail,key)) }}</dd></dl></section><section><h3>{{ uiText("待办（按确认计划，不凭建议判逾期）") }}</h3><p v-for="t in detail.tasks" :key="t.taskKey" :class="{warning:t.overdue}">{{ uiText(String((t.title) ?? '')) }} · {{ display(t.dueDate) }}{{ t.overdue?uiText(' · 已逾期'):'' }} · {{ options?.users?.find(u=>u.userId===t.ownerUserId)?.displayName || uiText('负责人待指定') }}</p></section></div><div class="actions"><el-button @click="router.push({path:'/molds',query:{moldSetId:detail.moldSetId}})">{{ uiText("打开模具台账") }}</el-button><el-button @click="loadHistory">{{ uiText("查看修改历史") }}</el-button></div><el-table v-if="history.length" :data="history"><el-table-column prop="fieldName" :label='uiText("字段")' /><el-table-column :label='uiText("修改")'><template #default="{row}">{{ display(row.before) }} → {{ display(row.after) }}</template></el-table-column><el-table-column prop="reason" :label='uiText("更正说明")' /><el-table-column prop="actorName" :label='uiText("真实填写账号")' /><el-table-column prop="changedAt" :label='uiText("时间")' /></el-table></div></el-drawer>
    <el-drawer v-model="review" :title='uiText("共用模具草稿与冲突")' size="90%"><section v-for="[key,d] in moldDrafts" :key="key" class="draft-card"><h3>{{ d.source.assetNumber }} · {{ d.source.styleName }} · {{ d.source.compatibleModels }}</h3><el-button link @click="reason(d.source)">{{ d.reason || uiText('填写日期更正 / 核对原因') }}</el-button><p v-for="[name,c] in d.changes" :key="name">{{ uiText(String((field(name)?.label || name) ?? '')) }}：{{ display(c.before) }} → {{ display(c.after) }}</p><el-alert v-if="d.result" :title="d.result.message" type="warning" :closable="false" /><div v-for="c in d.result?.conflicts" :key="c.field" class="conflict"><span>{{ uiText(String((c.label) ?? '')) }}{{ uiText(" · 当前 ") }}{{ display(c.current) }}{{ uiText(" · 我的修改 ") }}{{ display(c.attempted) }}</span><el-button @click="drafts.resolve(key,c.field,false)">{{ uiText("采用当前值") }}</el-button><el-button type="warning" @click="drafts.resolve(key,c.field,true)">{{ uiText("确认保留我的修改") }}</el-button></div></section><template #footer><el-button type="primary" :loading="saving" @click="save">{{ uiText("保存已核对修改") }}</el-button></template></el-drawer>
    <el-drawer v-model="noteVisible" :title='uiText("排程备注")' size="min(760px,96vw)"><div v-if="noteRow&&field('scheduleNotes')" class="schedule-note-editor"><p>{{noteRow.assetNumber}} · {{moldSetPhones(noteRow)}}</p><CollaborationCell :field="field('scheduleNotes')!" :value="value(noteRow,'scheduleNotes')" :editable="can(field('scheduleNotes')!)&&!noteRow.schedulePaused" :dirty="dirty(noteRow,'scheduleNotes')" @preview="edit(noteRow,field('scheduleNotes')!,$event,true)" @edit="edit(noteRow,field('scheduleNotes')!,$event)"/><p class="hint">{{ uiText("修改暂存到共用草稿，关闭后可继续核对并批量保存。") }}</p></div><template #footer><el-button size="small" @click="noteVisible=false">{{ uiText("关闭（保留草稿）") }}</el-button></template></el-drawer>
    <el-drawer v-model="taskDrawerVisible" class="mold-task-drawer" :title='uiText("待办处理")' size="min(1040px,96vw)" destroy-on-close @close="closeTask"><ScheduleTaskEditor v-if="selectedTask && options" ref="taskEditor" :task="selectedTask" :options="options" @scope="recordTaskScope" @history="taskHistory" /></el-drawer>
    <CollaborationImportDialog v-if="options" v-model="importVisible" kind="schedule" :options="options" @imported="workspace.changed();load()" />
  </main>
</template>

<style scoped>
.mold-schedule{height:100%;min-height:0;min-width:0;overflow:hidden;display:flex;flex-direction:column;gap:6px}
.task-location-note{display:flex;align-items:center;gap:6px;flex-wrap:wrap;font-size:12px;color:#627b94}.timeline-tasks button{border:0;background:transparent;padding:2px 0;color:#376aaf;cursor:pointer;text-align:left;white-space:normal;overflow-wrap:anywhere}
.wide-mode{position:fixed;inset:0;height:100dvh;z-index:1800;background:#f3f6fa;padding:8px}
.schedule-heading,.actions,.schedule-filters,.schedule-save{display:flex;align-items:center;gap:6px}
.schedule-heading{flex:none;min-height:28px;flex-wrap:wrap}.schedule-heading h2{font-size:16px;line-height:24px;margin:0 6px 0 0;white-space:nowrap}.heading-actions{margin-left:auto}.actions{flex-wrap:wrap}
.schedule-panel{flex:1;min-height:0;min-width:0;display:flex;flex-direction:column;gap:6px;background:white;border:1px solid #e4ebf4;border-radius:4px;padding:8px}
.schedule-filters{flex:none;flex-wrap:wrap}.schedule-filters>.el-input{min-width:220px;max-width:360px;flex:1}.schedule-filters>.el-select{width:124px}.record-count{margin-left:auto;color:#72859a;white-space:nowrap}
.hint-trigger{position:absolute;right:1px;top:-4px;z-index:1;width:12px;height:12px;border:0;border-radius:50%;padding:0;background:#dceaff;color:#326aaf;font-size:14px;line-height:10px;cursor:pointer}.hint-trigger:focus-visible{outline:2px solid #4d82ed}.schedule-help{font-size:12px;line-height:1.6;color:#52667f}.schedule-help p{margin:6px 0}
.asset-button{display:flex;align-items:center;gap:3px;width:100%;border:0;background:none;padding:0;color:#326aaf;cursor:pointer;font:inherit;white-space:nowrap}.row-dirty{width:5px;height:5px;flex:none;border-radius:50%;background:#b87913}.status-cell{display:flex;align-items:center;gap:3px}.review-marker{font-weight:700}.warning{color:#bb6b19}
.schedule-save{flex:none;min-height:36px;padding:4px 8px;background:#fff6d7;border:1px solid #eacc78;border-radius:4px;justify-content:space-between;flex-wrap:wrap;box-sizing:border-box}.schedule-save small{color:#957327;font-size:12px}
.schedule-parts{display:grid;grid-template-columns:repeat(2,minmax(410px,1fr));gap:16px;padding:12px;min-width:860px;background:#f5f8fd}.schedule-parts h3{font-size:12px;margin:0 0 8px}.part-entry{display:grid;grid-template-columns:160px minmax(220px,1fr);gap:10px;align-items:center;margin:8px 0}.schedule-empty{padding:24px;text-align:center;color:#8392a8}.schedule-panel .el-pagination{justify-content:flex-end;flex:none}
.baseline-grid{display:grid;grid-template-columns:repeat(3,minmax(300px,1fr));gap:24px;overflow:auto}.baseline-grid section{background:#f5f8fc;border-radius:8px;padding:20px}.baseline-grid dl{display:flex;justify-content:space-between;gap:15px;margin:12px 0}.baseline-grid dt{color:#7a8da3}.baseline-grid dd{margin:0}.schedule-detail{display:flex;flex-direction:column;gap:18px}.draft-card{border-bottom:1px solid #e0e8f2;padding:20px}.conflict{display:flex;gap:16px;align-items:center;margin-top:12px}
.schedule-timeline{flex:1;min-height:0;overflow:auto;display:flex;flex-direction:column;gap:12px}.timeline-note{margin:0;color:#6a7f98;font-size:12px;line-height:1.6}.timeline-card{flex:none;border:1px solid #dce6f2;border-radius:6px;padding:12px;background:#fff}.timeline-heading,.timeline-footer{display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap}.timeline-heading strong{font-size:12px}.timeline-heading p{color:#7b8ca1;font-size:12px;margin:6px 0}.timeline-track{display:grid;grid-template-columns:repeat(6,minmax(200px,1fr));gap:12px;overflow-x:auto;padding:8px 0}.timeline-step{position:relative}.timeline-step h3{display:flex;align-items:center;gap:6px;margin:0 0 8px;font-size:12px}.timeline-step h3 span{display:inline-grid;place-items:center;width:20px;height:20px;border-radius:50%;background:#e9f1ff;color:#376cad}.timeline-step:not(:last-child) h3:after{content:'';height:2px;background:#dde7f4;flex:1}
.timeline-layer{padding:8px;border-radius:4px;margin:6px 0;font-size:12px;min-height:56px;display:flex;flex-direction:column;gap:5px}.timeline-layer>label,.timeline-layer dt{font-size:12px;color:#6f839b}.timeline-layer.confirmed{background:#eff4fc}.timeline-layer.forecast{background:#fff7e6}.timeline-layer.actual{background:#f0f8f4}.timeline-layer dl{margin:0}.timeline-layer dd{margin:4px 0 0}.timeline-footer{margin-top:8px;justify-content:flex-start}.timeline-footer small{color:#7b8ca1;font-size:12px}
.timeline-parts{display:grid;grid-template-columns:repeat(2,minmax(410px,1fr));gap:16px;background:#f5f8fd;border-radius:4px;margin-top:10px;padding:12px;overflow:auto}.timeline-tasks{display:flex;gap:10px;flex-wrap:wrap;border-top:1px solid #e4ebf4;margin-top:12px;padding-top:10px;font-size:12px;color:#657f9b}.timeline-tasks strong{color:#385c85}
@media(max-width:1100px){.schedule-heading{gap:4px}.heading-actions{margin-left:0}.schedule-filters>.el-input{max-width:none}.timeline-parts{grid-template-columns:minmax(410px,1fr)}}


.schedule-table :deep(th .cell){font-size:12px}.application-head{font-size:12px}.schedule-tabs{flex:none;min-height:28px}.schedule-panel{overflow:hidden}.schedule-heading h2{white-space:normal}.asset-button{white-space:normal;overflow-wrap:anywhere}.schedule-table :deep(.cell){white-space:normal;overflow-wrap:anywhere}.schedule-cell :deep(.readonly-value){white-space:normal;overflow:visible;text-overflow:clip}.schedule-table :deep(.el-table__fixed-right),.schedule-table :deep(.el-table__fixed){overflow:visible}
.schedule-note-preview{display:block;width:100%;padding:0;border:0;background:none;text-align:left;white-space:pre-wrap;overflow-wrap:anywhere;font:inherit;line-height:16px;color:#334d6c;cursor:pointer}.schedule-note-preview>span{display:inline-block;margin-left:5px;color:#2c66b1;font-size:12px}.schedule-note-editor{font-size:12px}.schedule-note-editor :deep(textarea){min-height:220px;white-space:pre-wrap;overflow-wrap:anywhere;resize:vertical;line-height:1.6}.schedule-note-editor :deep(.readonly-value){display:block;white-space:pre-wrap;overflow-wrap:anywhere;overflow:visible;-webkit-line-clamp:unset}.application-backlinks{display:flex;flex-wrap:wrap;gap:6px}.hint{color:#718399;line-height:1.6}
.schedule-records,.application-list{flex:1;min-height:0;min-width:0;overflow:auto}.schedule-record{padding:8px;border-bottom:1px solid #e2e9f1;font-size:12px;line-height:16px}.schedule-record header{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.schedule-record header .asset-button{flex:1;min-width:150px}.schedule-record p{margin:4px 0;color:#677f97;overflow-wrap:anywhere}.record-fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:6px 10px}.record-fields>label{display:block;min-width:0}.record-fields>label>span{display:block;color:#708198;font-size:12px;margin-bottom:2px}.record-note{grid-column:1/-1}.record-fields :deep(.plan-cell){min-height:28px;font-size:12px;padding:0 3px}.record-fields :deep(input),.record-fields :deep(select){height:28px;padding:4px 0}.record-fields :deep(.readonly-value){display:block;white-space:normal;overflow:visible;-webkit-line-clamp:unset}.schedule-records .actions{margin-top:4px}.timeline-track{grid-template-columns:repeat(6,minmax(0,1fr));overflow:visible}.timeline-step{min-width:0;overflow-wrap:anywhere}.timeline-parts,.schedule-parts{grid-template-columns:repeat(2,minmax(0,1fr));min-width:0}.part-entry{grid-template-columns:minmax(110px,.7fr) minmax(0,1fr)}.baseline-grid{grid-template-columns:repeat(3,minmax(0,1fr));overflow:visible}.baseline-grid section{padding:12px;min-width:0}.baseline-grid dl{flex-wrap:wrap}.is-narrow .timeline-track{grid-template-columns:repeat(3,minmax(0,1fr))}.is-narrow .baseline-grid{grid-template-columns:1fr}.is-small .record-fields,.is-small .timeline-track{grid-template-columns:repeat(2,minmax(0,1fr))}.is-small .timeline-parts,.is-small .schedule-parts{grid-template-columns:1fr}
.application-grid{display:grid;grid-template-columns:minmax(210px,1.45fr) minmax(125px,.9fr) minmax(126px,.85fr) minmax(106px,.7fr) minmax(160px,1.05fr) minmax(140px,.95fr) minmax(204px,1.3fr) minmax(120px,.8fr);align-items:center;box-sizing:border-box;min-height:41px;border-bottom:1px solid #e3eaf3;font-size:12px;line-height:16px}.application-grid>div,.application-grid>span{min-width:0;padding:4px 6px;white-space:normal;overflow-wrap:anywhere}.application-grid small{display:block;color:#7b8fa3;font-size:12px;white-space:normal;overflow-wrap:anywhere}.application-head{position:sticky;top:0;background:#f6f8fc;z-index:1;font-weight:600;min-height:32px}.application-narrow .application-grid{grid-template-columns:repeat(4,minmax(0,1fr));gap:6px;padding:6px}.application-narrow .application-row>div::before{content:attr(data-label)'：';display:block;color:#7c8ca2;font-size:12px}.application-narrow .application-row>div:first-child{grid-column:span 2}.is-small .application-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.is-small .application-row>div:first-child{grid-column:1/-1}.schedule-panel .el-pagination{flex-wrap:wrap;max-width:100%;min-height:28px}.schedule-note-editor :deep(.plan-cell){align-items:stretch}.schedule-heading :deep(.el-button--small),.schedule-filters :deep(.el-input__wrapper),.schedule-filters :deep(.el-select__wrapper){font-size:12px;min-height:28px}

:global(.mold-task-drawer .el-drawer__header){margin-bottom:0;padding:20px 24px;border-bottom:1px solid #e8edf3;font-size:16px;color:#263b53}
:global(.mold-task-drawer .el-drawer__body){padding:24px;display:flex;flex-direction:column;min-height:0;overflow:hidden}
@media(max-width:650px){:global(.mold-task-drawer .el-drawer__header){padding:16px}:global(.mold-task-drawer .el-drawer__body){padding:16px}}
</style>
