<script setup lang="ts">
import { t as uiText } from '@/locales'

import {ref,watch} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage} from 'element-plus'
import * as api from '@/api/modules/moldApplications'
import {useCollaborationWorkspace} from '@/stores/collaborationWorkspace'
import {useUserStore} from '@/stores/user'
const visible=defineModel<boolean>({default:false}),router=useRouter(),workspace=useCollaborationWorkspace(),user=useUserStore()
const data=ref<api.HistoryPreview>(),loading=ref(false),applying=ref(false),error=ref(''),search=ref(''),results=ref<{applicationId:number;status:string;message?:string}[]>([])
let epoch=0
async function load(){const ticket=++epoch,token=user.token;loading.value=true;error.value='';try{const value=await api.getMoldHistoryReconciliation();if(ticket===epoch&&token===user.token)data.value=value}catch(e){if(ticket===epoch)error.value=e instanceof Error?e.message:'预检读取失败'}finally{if(ticket===epoch)loading.value=false}}
async function apply(group?:api.HistoryPreview['groups'][number]){const items=(group?.applications||data.value?.items||[]).filter(a=>a.actionable).map(({applicationId,version,fingerprint,phoneModelId})=>({applicationId,version,fingerprint,phoneModelId}));if(!items.length||applying.value)return;const token=user.token;applying.value=true;error.value='';try{const value=await api.applyMoldHistoryReconciliation(items,'按真实审批任务、标准手机、正式产品及历史导入证据补齐明确关系；缺失实物不补造');if(token!==user.token)return;data.value=value.remaining;results.value=value.results;workspace.changed();ElMessage.success(uiText('已补齐 {value0} 条申请的明确关联',{value0:(value.saved)}))}catch(e){if(token===user.token)error.value=e instanceof Error?e.message:'补链未完成，已保存记录可重新预检'}finally{if(token===user.token)applying.value=false}}
function go(path:string,query:Record<string,string|number>){visible.value=false;void router.push({path,query})}
watch(visible,v=>{if(v)void load()})
watch(()=>user.token,()=>{epoch++;visible.value=false;data.value=undefined;results.value=[];error.value='';loading.value=false;applying.value=false})
</script>
<template>
<el-drawer v-model="visible" :title='uiText("历史开模关联 · 预检与集中补录")' size="min(1160px,96vw)" :close-on-click-modal="!applying">
 <div class="history-review" v-loading="loading"><div class="review-tools"><span>{{ uiText("已检查 ") }}{{data?.total||0}}{{ uiText(" 份 · 完整关联 ") }}{{data?.linked||0}}{{ uiText(" 份 · 可补齐 ") }}{{data?.actionable||0}}{{ uiText(" 份") }}</span><el-button size="small" :disabled="applying" @click="load">{{ uiText("重新预检") }}</el-button><el-button size="small" type="primary" :loading="applying" :disabled="!data?.actionable" @click="apply()">{{ uiText("一键补齐明确关联") }}</el-button><el-input v-model="search" :placeholder='uiText("查手机、编码、款式或申请号")' clearable/></div>
 <p>{{ uiText("按手机集中列出已有证据和剩余缺项。项目、主档、具体套分层保存；补链不生成历史实物，不修改原审批结果。") }}</p><el-alert v-if="error" :title="error" type="error" :closable="false"/>
 <el-alert v-for="r in results.filter(r=>r.status==='conflict')" :key="r.applicationId" :title="uiText('申请 #{value0}：{value1}',{value0:(r.applicationId),value1:(r.message)})" type="warning" :closable="false"/>
 <section v-for="group in data?.groups.filter(g=>!search||JSON.stringify(g).toLowerCase().includes(search.toLowerCase()))" :key="group.groupKey" class="phone-group"><header><strong>{{group.phoneName}} · {{group.phoneCode||uiText('编码待分配')}}</strong><el-button v-if="group.applications.some(a=>a.actionable)" size="small" :loading="applying" @click="apply(group)">{{ uiText("补齐本组明确关联") }}</el-button></header>
 <article v-for="a in group.applications" :key="a.applicationId"><div class="application-heading"><b>{{a.productLineName||a.tipo}}</b><el-button size="small" link type="primary" @click="go('/mold-applications',{applicationId:a.applicationId})">{{a.approvalNo||uiText('申请 #{value0}',{value0:(a.applicationId)})}}</el-button><span>{{a.state==='linked'?uiText('关联已齐全'):a.state==='ended'?uiText('已结束 · 保留事实'):a.state==='test'?uiText('测试占位'):a.createProject?uiText('可登记项目'):a.actionable?uiText('可补明确关系'):uiText('待补资料')}}</span></div><p>{{ uiText("项目 ") }}{{a.projectId||uiText('尚无')}}{{ uiText(" · 主档 ") }}{{a.itemId||uiText('尚无')}}{{ uiText(" · 套 ") }}{{a.moldSetId||uiText('尚无')}}{{ uiText(" · 实例 ") }}{{a.instanceId||uiText('未提交')}}</p>
 <div v-for="gap in a.gaps" :key="gap.code" class="gap"><span>{{gap.message}}</span><el-button size="small" link type="primary" @click="go(gap.path,gap.query)">{{gap.code==='product_mapping'?uiText('维护共用产品映射'):gap.code==='set'?uiText('补具体套依据'):gap.code==='phone_identity'||gap.code==='network_conflict'?uiText('补手机关系'):uiText('打开办理位置')}}</el-button></div>
 <details v-if="a.evidence.length"><summary>{{ uiText("明确依据") }}</summary><pre>{{JSON.stringify(a.evidence,null,2)}}</pre></details></article></section></div>
</el-drawer>
</template>
<style scoped>
.history-review{font-size:12px}.history-review p{margin:5px 0;color:#6b7c90;overflow-wrap:anywhere}.review-tools{display:flex;gap:6px;align-items:center;flex-wrap:wrap}.review-tools .el-input{flex:1;min-width:180px}.phone-group{border:1px solid #dfe6ee;margin:10px 0;border-radius:4px}.phone-group header{display:flex;justify-content:space-between;align-items:center;background:#f4f7fb;padding:6px 10px;gap:8px}.phone-group article{padding:8px 10px;border-top:1px solid #e6ebf2}.application-heading,.gap{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.gap>span{flex:1;min-width:220px}.gap{color:#8a642e}details summary{cursor:pointer;color:#3670b0}pre{font:inherit;white-space:pre-wrap;overflow-wrap:anywhere;background:#f7f9fc;padding:8px}
</style>
