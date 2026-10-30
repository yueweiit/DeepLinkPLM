<script setup lang="ts">
import { t as uiText } from '@/locales'

import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/modules/schedule'
import type { ScheduleOptions, ScheduleRow, ScheduleTask } from '@/api/modules/schedule'
import type { CellValue, PlanField } from '@/types/collaboration'
import { useUserStore } from '@/stores/user'
import { useCollaborationWorkspace } from '@/stores/collaborationWorkspace'
import { archiveModelName, archivePlatformName, groupNames, parseCell, parseClipboard, rowKey, suggestedArrivalDate, validateMoldRow } from '@/utils/collaboration'
import CollaborationCell from '@/views/collaboration/components/CollaborationCell.vue'
const props=defineProps<{task:ScheduleTask;options:ScheduleOptions}>()
const emit=defineEmits<{scope:[id:number,fields:string[]];saved:[];history:[row:ScheduleRow]}>()
const user=useUserStore(),workspace=useCollaborationWorkspace(),drafts=workspace.drafts,router=useRouter()
const row=ref<ScheduleRow>(),detailLoading=ref(false),saving=ref(false),extra=ref(false),error=ref('')
let generation=0
const definition=computed(()=>props.options.taskDefinitions?.find(d=>d.taskKey===props.task.taskKey))
const inputKeys=computed(()=>props.task.inputFields || definition.value?.inputFields || [])
const supportKeys=computed(()=>props.task.supportFields || definition.value?.supportFields || [])
const scopedKeys=computed(()=>[...new Set([...inputKeys.value,...supportKeys.value])])
const scope=computed(()=>({entity:'mold_set' as const,id:props.task.moldSetId,fields:scopedKeys.value}))
const patches=computed(()=>drafts.patches(scope.value)),dirtyCount=computed(()=>patches.value.reduce((n,p)=>n+p.changes.length,0))
const allFields=computed(()=>[...new Map([...props.options.moldFields,...props.options.scheduleFields].map(f=>[f.key,f])).values()])
const fields=(keys:string[])=>keys.map(k=>allFields.value.find(f=>f.key===k)).filter((f):f is PlanField=>!!f)
const inputFields=computed(()=>fields(inputKeys.value)),supportFields=computed(()=>fields(supportKeys.value))
const writingKeys=new Set(['notes','productionNotes'])
const mainFields=computed(()=>inputFields.value.filter(f=>!writingKeys.has(f.key)))
const additionalFields=computed(()=>supportFields.value.filter(f=>!writingKeys.has(f.key)))
const writingFields=computed(()=>fields(scopedKeys.value).filter(f=>writingKeys.has(f.key)))
const can=(f:PlanField)=>f.group!=='finance' && user.hasPermission('schedule:write') && props.options.writableGroups.includes(f.group) && scopedKeys.value.includes(f.key) && (!row.value?.schedulePaused || !!props.options.pausedWritableKeys?.includes(f.key))
const value=(key:string):CellValue=>row.value?drafts.value(row.value,key):null
const taskValue=(key:string):CellValue=>scopedKeys.value.includes(key)?value(key):(row.value?.[key] as CellValue)??null
const dirty=(key:string)=>!!row.value&&!!drafts.rows.get(rowKey(row.value))?.changes.has(key)
const result=computed(()=>row.value?drafts.rows.get(rowKey(row.value))?.result:undefined)
const expectedKeys=computed(()=>patches.value.flatMap(p=>Object.keys(p.expectedValues || {})))
const expectedOnly=(key:string)=>expectedKeys.value.includes(key)&&!patches.value.some(p=>p.changes.some(c=>c.field===key))
const relevantConflicts=computed(()=>result.value?.conflicts.filter(c=>scopedKeys.value.includes(c.field)||expectedKeys.value.includes(c.field))||[])
const arrivalSuggestion=computed(()=>suggestedArrivalDate(taskValue('plannedMoldDeliveryDate')))
const arrivalField=computed(()=>allFields.value.find(f=>f.key==='plannedFactoryArrivalDate'))
async function refresh(){const token=user.token,ticket=++generation;row.value=undefined;error.value='';detailLoading.value=true;try{const data=await api.getSchedule(props.task.moldSetId);if(token===user.token&&ticket===generation)row.value=data}catch{if(token===user.token&&ticket===generation)error.value='当前任务资料加载失败，请重试'}finally{if(ticket===generation)detailLoading.value=false}}
watch(()=>props.task,()=>{extra.value=false;emit('scope',props.task.moldSetId,scopedKeys.value);void refresh()},{immediate:true})
watch(()=>user.token,()=>{generation++;row.value=undefined;error.value='';saving.value=false;extra.value=false},{flush:'sync'})
function edit(f:PlanField,text:string,quiet=false){if(!row.value||!can(f))return;try{drafts.set(row.value,f.key,parseCell(f,text))}catch(e){if(!quiet)ElMessage.warning(e instanceof Error?e.message:uiText('格式不正确'))}}
function paste(f:PlanField,event:ClipboardEvent){
  if((event.target as HTMLElement|null)?.tagName==='TEXTAREA')return
  const text=event.clipboardData?.getData('text/plain')||'';if(!/[\t\n\r]/.test(text))return;event.preventDefault()
  try{const cells=parseClipboard(text);if(cells.length!==1)throw new Error('任务录入只支持同一任务的一行字段，跨模具粘贴请在实物排程中操作')
    const current=[...mainFields.value,...(extra.value?additionalFields.value:[]),...writingFields.value],start=current.findIndex(field=>field.key===f.key)
    const changes=cells[0].map((text,i)=>{const field=current[start+i];if(!field||!can(field))throw new Error('粘贴超出当前任务或包含只读字段');return {field,value:parseCell(field,text)}})
    if(row.value)changes.forEach(c=>drafts.set(row.value!,c.field.key,c.value))
  }catch(e){ElMessage.warning(e instanceof Error?e.message:uiText('粘贴失败'))}
}
function adoptArrival(){const f=arrivalField.value;if(!row.value||!f||!can(f)||!arrivalSuggestion.value)return;if(value(f.key)){ElMessage.warning(uiText('已有到厂计划，建议不会覆盖，请手工修改并说明原因'));return}drafts.set(row.value,f.key,arrivalSuggestion.value,true)}
function confirmArrival(){const f=arrivalField.value;if(row.value&&f&&can(f)&&value(f.key))drafts.set(row.value,f.key,value(f.key),true)}
async function save(){
  if(!row.value||!patches.value.length||!user.token)return
  const validation=validateMoldRow(row.value,taskValue);if(validation){error.value=validation;return}
  const submitted=patches.value,token=user.token;saving.value=true;error.value=''
  try{const results=await api.saveScheduleRows(submitted);if(token!==user.token)return
    results.forEach(r=>{drafts.apply(r,submitted.find(p=>p.id===r.id));if(r.status==='saved'&&r.row&&row.value?.moldSetId===r.id)row.value=r.row as ScheduleRow})
    if(results.some(r=>r.status!=='saved'))error.value='当前任务未保存，校验结果和草稿已保留'
    else{workspace.changed();emit('saved');ElMessage.success(uiText('已保存，待办按正式记录重新判断'))}
  }catch{if(token===user.token)error.value='保存未确认完成，当前任务草稿已保留'}finally{if(token===user.token)saving.value=false}
}
async function discard(){if(!dirtyCount.value)return;try{await ElMessageBox.confirm(uiText('仅撤销当前任务字段的未保存修改；其他任务及模块草稿保留。'),uiText('撤销当前任务'));drafts.clear(scope.value);error.value=''}catch{}}
async function reason(){if(!row.value)return;const draft=drafts.rows.get(rowKey(row.value));if(!draft)return;try{const r=await ElMessageBox.prompt(uiText('填写本次更正或核对说明，随当前任务字段保存。'),uiText('更正说明'),{inputType:'textarea',inputValue:draft.reason});draft.reason=r.value.trim()}catch{}}
function resolve(field:string,keepMine:boolean){if(!row.value)return;const current=result.value?.conflicts.find(c=>c.field===field)?.current;drafts.resolve(rowKey(row.value),field,keepMine);if(current!==undefined)row.value[field]=current}
function navigate(path:string){void router.push({path,query:{moldSetId:props.task.moldSetId,...(path==='/mold-schedules'?{tab:'sets'}:{})}})}
defineExpose({refresh})
</script>
<template>
  <section class="task-editor" v-loading="detailLoading">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="row">
      <header class="task-context">
        <div class="editor-heading">
          <h3>{{ task.title }}</h3>
          <el-tag :type="task.overdue?'danger':'info'">{{ task.overdue?uiText('延期 {value0} 天',{value0:(task.overdueDays || 1)}):task.category==='review'?uiText('待核对'):task.category==='missing'?uiText('待补填'):uiText('待完成') }}</el-tag>
        </div>
        <div class="task-identity">
          <span :title="row.styleName">{{ archivePlatformName(row) }}</span>
          <span :title="row.compatibleModels">{{ archiveModelName(row) }}</span>
          <button @click="navigate('/molds')">{{ row.assetNumber }}</button>
        </div>
        <div class="task-meta">{{ groupNames[task.group as keyof typeof groupNames] || task.group }} · {{ task.ownerUserId ? options?.users?.find(u=>u.userId===task?.ownerUserId)?.displayName || uiText('账号 #{value0}',{value0:(task.ownerUserId)}) : uiText('按职责分配') }} · {{ task.dueDate?uiText('期限 {value0}',{value0:(task.dueDate)}):uiText('无独立期限，不作逾期提醒') }}</div>
      </header>

      <div class="task-form">
        <div v-if="mainFields.length" class="task-fields">
          <label v-for="f in mainFields" :key="f.key" class="task-field" :data-task-field="f.key">
            <span>{{ uiText(String((f.label) ?? '')) }}</span>
            <CollaborationCell :field="f" :value="value(f.key)" :editable="can(f)" :dirty="dirty(f.key)" :users="options?.users" @edit="edit(f,$event)" @preview="edit(f,$event,true)" @paste="paste(f,$event)" />
          </label>
        </div>
        <div v-if="inputKeys.includes('plannedFactoryArrivalDate')" class="arrival-confirmation">
          <span v-if="arrivalSuggestion">{{ uiText("建议 ") }}{{ arrivalSuggestion }}{{ uiText("（计划交付＋7天）") }}</span>
          <el-button v-if="arrivalSuggestion && !value('plannedFactoryArrivalDate')" :disabled="!arrivalField || !can(arrivalField)" data-test="adopt-task-arrival" @click="adoptArrival">{{ uiText("确认采用建议") }}</el-button>
          <template v-if="task.taskKey==='arrival_review'"><span>{{ uiText("计划交付变化，请核对已有到厂日期；不会自动覆盖。") }}</span><span>{{ uiText("上次依据 ") }}{{ row.arrivalConfirmedBasisDate || uiText('未确认') }}</span><el-button v-if="value('plannedFactoryArrivalDate')" :disabled="!arrivalField || !can(arrivalField)" data-test="confirm-task-arrival" @click="confirmArrival">{{ uiText("确认当前到厂计划") }}</el-button></template>
        </div>
        <details v-if="additionalFields.length" class="task-additional" :open="extra" @toggle="extra=($event.target as HTMLDetailsElement).open">
          <summary>{{ uiText("补充周期、预测与负责人") }}</summary>
          <div class="task-fields">
            <label v-for="f in additionalFields" :key="f.key" class="task-field" :data-task-field="f.key">
              <span>{{ uiText(String((f.label) ?? '')) }}</span>
              <CollaborationCell :field="f" :value="value(f.key)" :editable="can(f)" :dirty="dirty(f.key)" :users="options?.users" @edit="edit(f,$event)" @preview="edit(f,$event,true)" @paste="paste(f,$event)" />
            </label>
          </div>
        </details>
        <div v-if="writingFields.length" class="task-writing">
          <label v-for="f in writingFields" :key="f.key" class="task-field task-writing-field" :data-task-field="f.key">
            <span class="writing-label"><span>{{ uiText(String((f.label) ?? '')) }}</span><small v-if="f.maxLength">{{ String(value(f.key) ?? '').length }} / {{ f.maxLength }}</small></span>
            <CollaborationCell :field="f" :value="value(f.key)" :editable="can(f)" :dirty="dirty(f.key)" :users="options?.users" @edit="edit(f,$event)" @preview="edit(f,$event,true)" @paste="paste(f,$event)" />
          </label>
        </div>
        <p v-if="!inputFields.length && !writingFields.length" class="empty-note">{{ uiText("此任务暂未提供可填写字段，请刷新或前往排程查看。不会开放其他职责字段。") }}</p>
        <section v-if="result && result.status!=='saved'" class="task-conflicts">
          <strong>{{ result.message }}</strong>
          <div v-for="c in relevantConflicts" :key="c.field">{{ uiText(String((c.label) ?? '')) }}{{ uiText("：当前 ") }}{{ c.current ?? uiText('未填写') }}；{{ expectedOnly(c.field)?uiText('原核对依据'):uiText('待保存') }} {{ (expectedOnly(c.field)?c.before:c.attempted) ?? uiText('未填写') }}<template v-if="expectedOnly(c.field)"><el-button :data-test="`review-expected-${c.field}`" @click="resolve(c.field,true)">{{ uiText("已核对最新依据") }}</el-button></template><template v-else><el-button @click="resolve(c.field,false)">{{ uiText("采用当前值") }}</el-button><el-button @click="resolve(c.field,true)">{{ uiText("确认保留我的修改") }}</el-button></template></div>
        </section>
      </div>

      <footer class="task-footer">
        <p class="draft-note">{{ uiText("浅黄色为尚未保存的修改。") }}</p>
        <p v-if="scopedKeys.some(k=>/MexicoArrival|FactoryArrival|Acceptance/.test(k))" class="draft-note">{{ uiText("抵墨不等同于到厂或验收；正式计划基准在开模时间管控确认。") }}</p>
        <div class="task-actions">
          <span class="save-state" :class="{'has-changes':dirtyCount}" aria-live="polite">{{ dirtyCount ? uiText('{value0} 个字段待保存',{value0:(dirtyCount)}) : uiText('当前没有未保存的修改') }}</span>
          <div class="save-buttons">
            <el-button :disabled="!dirtyCount || saving" @click="reason">{{ uiText("更正说明") }}</el-button>
            <el-button :disabled="!dirtyCount || saving" data-test="discard-current-task" @click="discard">{{ uiText("撤销当前任务") }}</el-button>
            <el-button type="primary" :disabled="!dirtyCount || saving" :loading="saving" data-test="save-current-task" @click="save">{{ uiText("保存当前任务") }}</el-button>
          </div>
        </div>
        <div class="editor-links">
          <el-button link @click="navigate('/mold-schedules')">{{ uiText("打开本套排程") }}</el-button>
          <el-button link @click="navigate('/molds')">{{ uiText("打开模具台账") }}</el-button>
          <el-button link @click="emit('history',row)">{{ uiText("修改历史") }}</el-button>
        </div>
      </footer>
    </template>
    <div v-else-if="!detailLoading" class="task-empty"><p>{{ uiText("任务资料尚未加载，不开放其他职责字段。") }}</p><el-button @click="refresh">{{ uiText("重试") }}</el-button></div>
  </section>
</template>
<style scoped>
.task-editor{display:flex;flex-direction:column;gap:22px;height:100%;min-height:0;min-width:0;font-size:14px;line-height:1.6;container-type:inline-size}
.task-context{flex-shrink:0;display:flex;flex-direction:column;gap:9px;padding-bottom:18px;border-bottom:1px solid #e7edf4}
.editor-heading,.task-identity,.arrival-confirmation,.task-actions,.save-buttons,.editor-links{display:flex;align-items:center;gap:12px;flex-wrap:wrap}
h3{font-size:18px;line-height:1.5;margin:0;color:#24374f;font-weight:600}
.task-identity{font-weight:500;color:#354b65;overflow-wrap:anywhere;gap:6px 12px}
.task-identity>button{border:0;background:none;color:#376aaf;cursor:pointer;font:inherit;padding:0}
.task-meta,.draft-note{color:#728197;font-size:13px;line-height:1.6}
.task-form{flex:1;min-height:0;overflow-y:auto;display:flex;flex-direction:column;gap:22px;padding:2px 3px 4px;scrollbar-width:thin}
.task-form>*{flex-shrink:0}
.task-fields{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,260px),1fr));gap:18px 20px}
.task-field{min-width:0;display:flex;flex-direction:column;gap:8px}
.task-field>span{display:block;color:#50627a;font-size:13px;font-weight:500;line-height:20px}
.task-field:has(textarea){grid-column:1/-1}
.task-field :deep(.plan-cell){height:auto;min-height:42px;min-width:0;background:#fff;border:1px solid #d9e2ed;border-radius:8px;padding:0 12px;box-sizing:border-box;transition:border-color .15s,box-shadow .15s}
.task-field :deep(.plan-cell:focus-within){border-color:#6d96ee;box-shadow:0 0 0 3px #edf3ff}
.task-field :deep(.is-dirty){background:#fffcf2;border-color:#e1bc70}
.task-field :deep(input),.task-field :deep(select){font-size:14px;line-height:22px;padding:10px 0;box-sizing:border-box}
.task-field :deep(textarea){white-space:pre-wrap;font-size:14px;line-height:1.75;min-height:180px;padding:12px 0;resize:vertical;box-sizing:border-box;overflow-wrap:anywhere}
.task-field :deep(input:focus),.task-field :deep(select:focus),.task-field :deep(textarea:focus){outline:none}
.task-writing{display:flex;flex:1;min-height:260px;flex-direction:column;gap:22px}
.task-writing-field{flex:1;min-height:240px}
.task-writing-field>.writing-label{display:flex;align-items:center;justify-content:space-between;gap:12px}
.writing-label small{font-size:12px;font-weight:400;color:#96a1b1}
.task-writing-field :deep(.plan-cell){flex:1;align-items:stretch}
.task-writing-field :deep(textarea){flex:1;height:100%;min-height:220px;resize:vertical}
.task-writing-field :deep(.readonly-value){display:block;white-space:pre-wrap;overflow-wrap:anywhere;overflow:auto;-webkit-line-clamp:unset;line-height:1.75;padding:12px 0}
summary{cursor:pointer;color:#54779d;font-size:13px;line-height:22px}
.task-additional[open]>summary{margin-bottom:16px}
.arrival-confirmation{background:#f5f8fc;border-radius:8px;padding:12px;color:#536a86;font-size:13px}
.task-conflicts{padding:14px;background:#fff6e9;border-radius:8px;overflow-wrap:anywhere}
.task-conflicts>div{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
.task-footer{flex-shrink:0;display:flex;flex-direction:column;gap:12px;padding-top:16px;border-top:1px solid #e3e9f2;background:#fff}
.draft-note{margin:0;font-size:12px}
.task-actions{justify-content:space-between;gap:12px}
.save-state{font-size:13px;color:#8a97a8}.save-state.has-changes{color:#9b732c}
.save-buttons{gap:8px}.save-buttons .el-button{min-height:40px;margin:0;padding:10px 16px}
.editor-links{gap:16px}.editor-links .el-button{margin:0;font-size:13px;color:#6f819a;line-height:1.6}
.empty-note{color:#8b6840}
@container(max-width:600px){.task-fields{grid-template-columns:1fr}.task-actions{align-items:stretch;flex-direction:column}.save-buttons{justify-content:flex-end}.save-buttons .el-button{flex:1;min-width:0;padding:9px 8px}.task-editor{gap:16px}.task-writing{min-height:220px}}
</style>
