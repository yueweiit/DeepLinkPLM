<script setup lang="ts">
import { t as uiText, currentLanguage } from '@/locales'

import {computed,onActivated,onDeactivated,onBeforeUnmount,onMounted,ref} from 'vue'
import {ElMessage} from 'element-plus'
import * as api from '@/api/modules/moldSheetSync'
import type {PlanField} from '@/types/collaboration'
const props=defineProps<{dirty:boolean;fields:PlanField[]}>()
const emit=defineEmits<{updated:[]}>()
const status=ref<api.SheetSyncStatus>(),error=ref(''),busy=ref(false),visible=ref(false)
const fieldInfo=ref<api.SheetSyncFields>(),fieldSet=ref<number>(),fieldVisible=ref(false),resumeKeys=ref<string[]>([]),resumeReason=ref('')
const selected=ref<Record<string,number|undefined>>({}),bindingReason=ref<Record<string,string>>({})
let alive=true,timer:ReturnType<typeof setTimeout>|undefined,wasRunning=false,lastEmitted=''
const rows=computed(()=>status.value?.job?.result.rows||[])
const frequencyText=computed(()=>{
  const minutes=status.value?.intervalMinutes
  if(minutes==null)return '读取中…'
  if(minutes===1440)return '每天一次'
  if(minutes===60)return '每小时一次'
  return minutes%60===0?uiText('每{hours}小时一次',{hours:minutes/60}):uiText('每{minutes}分钟一次',{minutes})
})
const label=(key:string)=>props.fields.find(f=>f.key===key)?.label||key
function selectResume(key:string,checked:unknown){resumeKeys.value=checked?[...new Set([...resumeKeys.value,key])]:resumeKeys.value.filter(k=>k!==key)}
const dateText=(value:string|null|undefined)=>value?new Date(value).toLocaleString(currentLanguage.value==='es'?'es-MX':'zh-CN',{hour12:false}):uiText('尚未成功更新')
function take(value:api.SheetSyncStatus){
  if(!alive)return
  if(status.value&&value.version<status.value.version)return
  const previousJob=status.value?.job?.run_id
  const completed=(wasRunning||!!previousJob&&previousJob!==value.job?.run_id)&&!value.running&&value.job?.status==='completed'&&value.job.run_id!==lastEmitted
  status.value=value;wasRunning=value.running
  if(completed){lastEmitted=value.job!.run_id;emit('updated')}
  if(timer)clearTimeout(timer)
  if(value.running||value.automatic)timer=setTimeout(()=>void load(),value.running?1500:60000)
}
async function load(){try{const value=await api.getMoldSheetSync();if(alive)error.value='';take(value)}catch(e){if(alive){error.value=e instanceof Error?e.message:'读取同步状态失败';if(status.value?.running||status.value?.automatic)timer=setTimeout(()=>void load(),status.value.running?3000:60000)}}}
function open(){visible.value=true;void load()}
async function update(){
  if(props.dirty){ElMessage.warning(uiText('请先保存本页日期草稿，再更新在线表'));return}
  busy.value=true;error.value=''
  try{take(await api.updateMoldSheet());visible.value=true}catch(e){error.value=e instanceof Error?e.message:'更新未开始，原日期保留'}finally{busy.value=false}
}
async function settings(automatic:boolean){
  if(!status.value)return
  busy.value=true;error.value=''
  try{take(await api.saveMoldSheetSettings({version:status.value.version,automatic,intervalMinutes:status.value.intervalMinutes}))}catch(e){error.value=e instanceof Error?e.message:'设置未保存';await load()}finally{busy.value=false}
}
async function inspect(id:number){
  busy.value=true;error.value=''
  try{fieldInfo.value=await api.getMoldSheetFields(id);fieldSet.value=id;resumeKeys.value=[];resumeReason.value='';fieldVisible.value=true}catch(e){error.value=e instanceof Error?e.message:'读取来源失败'}finally{busy.value=false}
}
async function resume(){
  if(!fieldSet.value||!fieldInfo.value?.version_no||!resumeKeys.value.length||!resumeReason.value.trim())return
  busy.value=true
  try{fieldInfo.value=await api.resumeMoldSheetFields(fieldSet.value,{version:fieldInfo.value.version_no,fields:resumeKeys.value,reason:resumeReason.value.trim()});resumeKeys.value=[];resumeReason.value='';ElMessage.success(uiText('已恢复跟随，下次更新采用在线表的最新值'))}catch(e){error.value=e instanceof Error?e.message:'恢复未完成'}finally{busy.value=false}
}
async function bind(row:api.SheetSyncRow){
  const id=selected.value[row.sourceKey],reason=bindingReason.value[row.sourceKey]?.trim()
  if(!id||!reason)return
  if(props.dirty){ElMessage.warning(uiText('请先保存本页日期草稿'));return}
  busy.value=true;error.value=''
  try{take(await api.bindMoldSheet({sourceKey:row.sourceKey,moldSetId:id,reason}))}catch(e){error.value=e instanceof Error?e.message:'关联未保存'}finally{busy.value=false}
}
onMounted(()=>void load())
onActivated(()=>{if(!alive){alive=true;void load()}})
onDeactivated(()=>{alive=false;if(timer)clearTimeout(timer)})
onBeforeUnmount(()=>{alive=false;if(timer)clearTimeout(timer)})
defineExpose({load})
</script>
<template>
  <div class="sheet-sync-controls" data-test="sheet-sync-controls">
    <el-button size="small" :loading="busy||status?.running" :disabled="dirty||!!status?.running" :aria-label='uiText("立即更新在线开模表")' @click="update">{{ uiText("立即更新") }}</el-button>
    <el-switch :model-value="status?.automatic||false" size="small" :aria-label='uiText("自动更新在线开模表")' :disabled="busy||!status?.initializedAt||!!status?.running" @change="settings(Boolean($event))"/><span class="auto-label">{{uiText(frequencyText)}}</span>
    <el-button text size="small" @click="open">{{status?.lastError?uiText('更新异常'):status?.lastSuccessAt?uiText('同步记录'):uiText('在线表设置')}}</el-button>
    <el-dialog v-model="visible" :title='uiText("在线表更新")' width="min(980px,96vw)" top="4vh" class="sheet-sync-dialog" :close-on-click-modal="false">
      <div class="sheet-sync-content">
        <p>{{ uiText("首次采用在线表中明确填写的日期，旧值保留历史。之后系统人工改过的时间优先，手动和自动更新都不会覆盖。") }}</p>
        <div class="source-actions"><el-link v-if="status" :href="status.sourceUrl" target="_blank" rel="noopener noreferrer">{{ uiText("打开钉钉源表") }}</el-link><span>{{ uiText("最近成功：") }}{{dateText(status?.lastSuccessAt)}}</span></div>
        <div class="source-settings"><span>{{ uiText("自动更新频率") }}</span><strong>{{uiText(frequencyText)}}</strong></div>
        <el-alert v-if="error||status?.lastError" type="error" :closable="false" :title="error||status?.lastError||''"/>
        <el-alert v-if="status?.running" type="info" :closable="false" :title='uiText("正在读取并核对在线表，已有排程保留")'/>
        <p v-if="status?.job?.result.sourceRows">{{ uiText("来源 ") }}{{status.job.result.sourceRows}}{{ uiText(" 条 · 已对应 ") }}{{status.job.result.matched}}{{ uiText(" 条 · 待核对 ") }}{{status.job.result.needsReview}}{{ uiText(" 条") }}</p>
        <div v-for="row in rows" :key="row.sourceKey" class="sync-source-row" :data-source-row="row.rowNo">
          <div><strong>{{ uiText("第 ") }}{{row.rowNo}}{{ uiText(" 行 · ") }}{{row.style}}</strong><p>{{row.model}}</p></div>
          <div><span>{{row.state==='updated'?uiText('已对应现有模具套'):uiText('待核对')}}</span><p v-if="row.changedFields?.length">{{ uiText("已更新：") }}{{row.changedFields.map(label).join('、')}}</p><p v-if="row.protectedFields?.length">{{ uiText("保留人工值：") }}{{row.protectedFields.map(label).join('、')}}</p><p v-if="row.message">{{row.message}}</p></div>
          <el-button v-if="row.moldSetId" link type="primary" @click="inspect(row.moldSetId)">{{ uiText("来源日期与人工保护") }}</el-button>
          <div v-else-if="row.candidates.length" class="source-binding"><el-select v-model="selected[row.sourceKey]" :placeholder='uiText("选择核对后的现有模具套")' :aria-label='uiText("源表候选模具套")'><el-option v-for="id in row.candidates" :key="id" :value="id" :label="uiText('模具套 #{value0}',{value0:(id)})"/></el-select><el-input v-model="bindingReason[row.sourceKey]" :placeholder='uiText("说明本行为什么对应这一套")' :aria-label='uiText("源表关联原因")'/><el-button size="small" :disabled="busy||!selected[row.sourceKey]||!bindingReason[row.sourceKey]?.trim()" @click="bind(row)">{{ uiText("关联并采用本行日期") }}</el-button></div>
        </div>
        <p v-if="!status?.initializedAt&&!status?.running">{{ uiText("点击“立即更新”建立首次基准；不会创建新模具、验收结果或生产数量。") }}</p>
      </div>
      <template #footer><el-button @click="visible=false">{{ uiText("关闭") }}</el-button><el-button type="primary" :disabled="dirty||busy||!!status?.running" @click="update">{{ uiText("立即更新") }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="fieldVisible" :title='uiText("来源日期与人工保护")' width="min(760px,96vw)" append-to-body>
      <p>{{fieldInfo?.identity?.style}} · {{fieldInfo?.identity?.model}}{{ uiText(" · 源表第 ") }}{{fieldInfo?.source_row_no}}{{ uiText(" 行") }}</p>
      <p v-if="!fieldInfo?.linked">{{ uiText("当前模具套还没有在线表关联。") }}</p>
      <div v-for="(field,key) in fieldInfo?.fields||{}" :key="key" class="source-field-row"><strong>{{uiText(label(String(key)))}}</strong><span>{{ uiText("源表：") }}{{field.sourceValue||uiText('未填写')}}</span><span>{{ uiText("系统：") }}{{field.currentValue||uiText('未填写')}}</span><span>{{field.manual?uiText('人工维护，更新不覆盖'):uiText('跟随在线表')}}</span><el-checkbox v-if="field.manual" :model-value="resumeKeys.includes(String(key))" @change="selectResume(String(key),$event)">{{ uiText("恢复跟随") }}</el-checkbox></div>
      <div v-if="fieldInfo?.source_notes" class="source-observations"><strong>{{ uiText("源表观察／备注") }}</strong><p>{{fieldInfo.source_notes}}</p></div>
      <el-input v-if="resumeKeys.length" v-model="resumeReason" type="textarea" :autosize="{minRows:2,maxRows:5}" :placeholder='uiText("填写恢复跟随的原因")' :aria-label='uiText("恢复源表跟随原因")'/>
      <template #footer><el-button @click="fieldVisible=false">{{ uiText("关闭") }}</el-button><el-button v-if="resumeKeys.length" type="primary" :disabled="busy||!resumeReason.trim()" @click="resume">{{ uiText("确认恢复跟随") }}</el-button></template>
    </el-dialog>
  </div>
</template>
<style scoped>
.sheet-sync-controls{display:flex;align-items:center;gap:5px;flex-wrap:wrap;min-width:0}.auto-label{font-size:12px;white-space:nowrap}.sheet-sync-content{max-height:66vh;overflow:auto;min-width:0}.source-actions,.source-settings{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:12px 0}.source-actions span{color:var(--el-text-color-secondary);font-size:12px}.sync-source-row{display:flex;align-items:flex-start;gap:12px;flex-wrap:wrap;padding:12px 0;border-top:1px solid var(--el-border-color-lighter)}.sync-source-row>div{flex:1 1 240px;min-width:0}.sync-source-row p,.source-observations p{margin:5px 0;white-space:pre-wrap;overflow-wrap:anywhere}.source-binding{display:flex;flex-wrap:wrap;gap:8px;flex-basis:100%!important}.source-binding .el-select,.source-binding .el-input{flex:1 1 200px;min-width:0}.source-field-row{display:flex;align-items:center;flex-wrap:wrap;gap:8px;padding:10px 0;border-bottom:1px solid var(--el-border-color-lighter)}.source-field-row>*{flex:1 1 145px;min-width:0;overflow-wrap:anywhere}.source-observations{margin-top:14px}
</style>
