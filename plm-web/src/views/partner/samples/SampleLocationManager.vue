<script setup lang="ts">
import {computed,onBeforeUnmount,reactive,ref,watch} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {t as uiText} from '@/locales'
import {useUserStore} from '@/stores/user'
import {newRequestId} from '@/api/modules/partner'
import {sampleLocations,deleteSampleLocation,sampleLocationReferences} from '@/api/modules/sampleManagement'
import type {SampleLocationOption,SampleLocationSite,SamplePage} from '@/types/sampleManagement'
import {sampleLocationSites,sampleLocationAccess} from '@/utils/sampleLocation'
import {partnerError} from '@/utils/partnerError'
import {useSampleLocationOptions} from './useSampleLocationOptions'
import SampleLocationEditor from './SampleLocationEditor.vue'

const emit=defineEmits<{close:[];changed:[value?:SampleLocationOption]}>(),user=useUserStore(),options=useSampleLocationOptions()
const query=reactive({keyword:'',site:(sampleLocationAccess(user).localOnly?'MX':'CN') as SampleLocationSite|'',status:'active',page:1}),data=ref<SamplePage<SampleLocationOption>>(),loading=ref(false),error=ref(''),editing=ref<SampleLocationOption>(),editorOpen=ref(false),deleting=ref<number>(),referenceInfo=ref<Awaited<ReturnType<typeof sampleLocationReferences>>>(),referenceOpen=ref(false),access=computed(()=>sampleLocationAccess(user))
const requests=new Map<number,{requestId:string;version:number}>();let generation=0,disposed=false
async function load(){const ticket=++generation,auth=user.token;loading.value=true;error.value='';try{const result=await sampleLocations({...query,site:query.site||undefined,status:query.status||undefined,size:50});if(!disposed&&ticket===generation&&auth===user.token)data.value=result}catch(e){if(!disposed&&ticket===generation)error.value=partnerError(e)}finally{if(ticket===generation)loading.value=false}}
function description(row:SampleLocationOption){const label=sampleLocationSites.find(site=>site.value===row.site)?.label||row.site,site=uiText(label);return !row.name||row.name===label||row.name===site?site:row.name+' · '+site}
function edit(row?:SampleLocationOption){editing.value=row;editorOpen.value=true}
async function saved(row:SampleLocationOption){editorOpen.value=false;await Promise.all([load(),options.reload()]);emit('changed',row);ElMessage.success(uiText('位置已保存'))}
async function viewReferences(row:SampleLocationOption){try{referenceInfo.value=await sampleLocationReferences(row.id);referenceOpen.value=true}catch(e){error.value=partnerError(e)}}

function referenceAction(action?:string){return uiText(({move:'移动位置',receive:'实际收样',opening:'期初登记',sample_location_set:'设置位置',sample_location_clear:'清除库位',sample_bulk_move:'批量设置位置'} as Record<string,string>)[action||'']||'历史流转')}
async function remove(row:SampleLocationOption){
 if(row.referenced||row.referenceCount){await viewReferences(row);return}
 const auth=user.token;if(deleting.value||!access.value.canWrite||row.canWrite===false)return
 try{await ElMessageBox.confirm(uiText('无引用位置将移到已删除列表，可恢复。'),row.locationCode||row.name,{confirmButtonText:uiText('删除位置'),cancelButtonText:uiText('取消'),type:'warning'})}catch{return}
 if(disposed||auth!==user.token)return
 const previous=requests.get(row.id),payload=previous?.version===row.version?previous:{requestId:newRequestId(),version:row.version};requests.set(row.id,payload);deleting.value=row.id;error.value=''
 try{await deleteSampleLocation(row.id,payload);if(disposed)return;requests.delete(row.id);await Promise.all([load(),options.reload()]);emit('changed');ElMessage.success(uiText('已从可选位置移除，原样品记录保留'))}catch(e){if(!disposed)error.value=partnerError(e)}finally{deleting.value=undefined}
}
watch(()=>[query.site,query.status],()=>{query.page=1;void load()})
watch(()=>user.token,()=>emit('close'))
void load();onBeforeUnmount(()=>{disposed=true;++generation})
</script>
<template>
 <el-drawer :model-value="true" :title="uiText('位置管理')" size="min(900px,96vw)" @close="emit('close')"><section class="sample-location-manager">
  <div class="manager-tools"><el-input v-model="query.keyword" size="small" clearable :placeholder="uiText('搜索位置码或说明')" :aria-label="uiText('搜索位置')" @keyup.enter="query.page=1;load()"/><el-select v-model="query.site" size="small" :disabled="access.localOnly" :clearable="!access.localOnly" :placeholder="uiText('全部站点')"><el-option v-for="site in sampleLocationSites.filter(value=>!access.localOnly||value.value==='MX')" :key="site.value" :value="site.value" :label="uiText(site.label)"/></el-select><el-select v-model="query.status" size="small" clearable :placeholder="uiText('全部状态')"><el-option value="active" :label="uiText('可选')"/><el-option value="inactive" :label="uiText('停用')"/><el-option value="deleted" :label="uiText('已删除')"/></el-select><el-button size="small" @click="query.page=1;load()">{{uiText('查询')}}</el-button><el-button v-if="access.canWrite" size="small" type="primary" @click="edit()">{{uiText('新增位置')}}</el-button></div>
  <p class="manager-note">{{uiText('位置码选填，可先填写位置名称；有引用的位置可停用并查看引用')}}</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load">{{uiText('重试')}}</el-button></el-alert>
  <div class="location-table" v-loading="loading"><article v-for="row in data?.content" :key="row.id" class="location-row"><div><strong>{{row.locationCode}}</strong><span>{{description(row)}}</span></div><span>{{uiText(row.deleted?'已删除':row.status==='active'?'可选':'停用')}}</span><div v-if="access.canWrite&&row.canWrite!==false" class="row-actions"><el-button link size="small" @click="edit(row)">{{uiText(row.deleted?'恢复／修改':'修改')}}</el-button><el-button v-if="row.referenced||row.referenceCount" link size="small" @click="viewReferences(row)">{{uiText('查看引用')}}<template v-if="row.referenceCount">（{{row.referenceCount}}）</template></el-button><el-button v-if="!row.deleted&&!row.referenced&&!row.referenceCount" link type="danger" size="small" :loading="deleting===row.id" @click="remove(row)">{{uiText('删除')}}</el-button></div></article><el-empty v-if="data&&!data.content.length" :description="uiText('暂无登记位置，可新增后快捷使用')"/></div>
  <footer><el-pagination v-model:current-page="query.page" :page-size="50" :total="data?.totalElements||0" layout="total,prev,pager,next" size="small" @current-change="load"/></footer>
 </section><SampleLocationEditor v-if="editorOpen" :key="editing?.id||'new'" :location="editing" :initial-site="query.site||'CN'" @close="editorOpen=false" @saved="saved"/><el-dialog v-model="referenceOpen" :title="uiText('位置引用')" width="min(620px,96vw)" append-to-body><template v-if="referenceInfo"><p>{{uiText('当前在库')}} {{referenceInfo.onhand}} · {{uiText('引用记录')}} {{referenceInfo.referenceCount}}</p><p>{{uiText(referenceInfo.referenced&&!referenceInfo.referenceCount?'存在其他引用，仅可停用':'有引用的位置仅可停用，原样品数量和历史保留。')}}</p><p>{{uiText('引用业务记录')}} {{referenceInfo.records}} · {{uiText('收样批次数')}} {{referenceInfo.batches}} · {{uiText('历史流转事件')}} {{referenceInfo.events}}</p><div class="reference-list"><article v-for="(reference,index) in referenceInfo.references" :key="index" class="reference-row"><template v-if="reference.type==='sample'"><strong>{{reference.sampleCode}} · {{reference.name}}</strong><span>{{uiText('本库位在库')}} {{reference.onhand}}</span></template><template v-else-if="reference.type==='shipment'"><strong>{{reference.code}} · {{reference.name}}</strong><span>{{uiText('寄样资料引用')}}</span></template><template v-else><strong>{{reference.sampleCode}} · {{referenceAction(reference.action)}}</strong><span>{{reference.date}} · {{uiText('数量')}} {{reference.qty}}</span></template></article></div></template></el-dialog></el-drawer>
</template>
<style scoped>.sample-location-manager{height:100%;display:flex;flex-direction:column;gap:8px;min-height:0;min-width:0;font-size:12px;container-type:inline-size}.manager-tools{display:flex;gap:6px;flex-wrap:wrap}.manager-tools>.el-input{flex:1;min-width:180px}.manager-tools>.el-select{width:110px}.manager-note{margin:0;color:#71849b;line-height:1.6}.location-table{flex:1;min-height:0;overflow:auto;border:1px solid #e0e7f0}.location-row{display:grid;grid-template-columns:minmax(0,1fr) 54px 88px;gap:8px;align-items:center;padding:8px;border-bottom:1px solid #e7edf4}.location-row strong,.location-row span{display:block;overflow-wrap:anywhere;line-height:1.6}.location-row strong{color:#2e506f}.row-actions{display:flex;gap:4px;flex-wrap:wrap}.reference-list{max-height:42vh;overflow:auto}.reference-row{display:flex;flex-direction:column;gap:3px;padding:7px 0;border-bottom:1px solid #e4ebf3;overflow-wrap:anywhere}.reference-row strong{font-size:12px}.reference-row span{font-size:12px;color:#64788f}.row-actions :deep(.el-button+.el-button){margin-left:0}footer{display:flex;justify-content:flex-end}@container(max-width:480px){.location-row{grid-template-columns:minmax(0,1fr) 80px}.location-row>span{grid-column:1}.row-actions{grid-column:2;grid-row:1/3}}</style>
