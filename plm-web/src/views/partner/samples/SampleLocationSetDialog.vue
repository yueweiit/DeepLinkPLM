<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {t as uiText} from '@/locales'
import {useUserStore} from '@/stores/user'
import {newRequestId} from '@/api/modules/partner'
import {querySampleIdentities,setSampleLocations} from '@/api/modules/sampleManagement'
import type {SampleIdentity,SampleLocationOption,SampleLocationSite} from '@/types/sampleManagement'
import {sampleLocationLabel,sampleLocationSites,sampleLocationAccess} from '@/utils/sampleLocation'
import {partnerError} from '@/utils/partnerError'
import {useSampleSubmission} from './useSampleSubmission'
import {useSampleLocationOptions} from './useSampleLocationOptions'
import SampleLocationEditor from './SampleLocationEditor.vue'

const props=defineProps<{samples:SampleIdentity[]}>(),emit=defineEmits<{close:[];saved:[]}>(),user=useUserStore(),auth=user.token
const ids=[...new Set(props.samples.map(sample=>sample.sampleIdentityId))].sort((a,b)=>a-b)
const {busy,error,unknown,pending,submit}=useSampleSubmission('location-set:'+ids.join(',')),options=useSampleLocationOptions()
const mode=ref<'all_onhand'|'clear_code'>(pending.value?.mode==='clear_code'?'clear_code':'all_onhand'),target=ref<number|undefined>(Number(pending.value?.targetLocationId)||undefined),chosen=ref<SampleLocationOption>(),site=ref<SampleLocationSite>(pending.value?.site==='MX'?'MX':sampleLocationAccess(user).localOnly?'MX':'CN'),sourceLocationId=ref<number|undefined>(Number(pending.value?.sourceLocationId)||undefined),keyword=ref(''),rows=ref<SampleIdentity[]>(props.samples),loading=ref(true),ready=ref(false),editorOpen=ref(false),date=ref(String(pending.value?.date||'')),reason=ref(String(pending.value?.reason||''))
const requestId=String(pending.value?.requestId||newRequestId()),access=computed(()=>sampleLocationAccess(user)),canWrite=computed(()=>access.value.canWrite)
const onhand=computed(()=>rows.value.reduce((total,row)=>total+sitePositions(row).reduce((qty,p)=>qty+p.onhand,0),0)),targetOptions=computed(()=>options.rows.value.filter(row=>row.site===site.value)),sources=computed(()=>[...new Map(rows.value.flatMap(row=>sitePositions(row)).filter(p=>p.locationId).map(p=>[p.locationId!,p])).values()])
function sitePositions(row:SampleIdentity){return (row.positions||row.balances.positions||[]).filter(p=>p.site===site.value&&p.onhand>0)}
let disposed=false,searchTimer:ReturnType<typeof setTimeout>|undefined
async function loadOptions(){await options.load({site:site.value,keyword:keyword.value.trim()})}
function search(value:string){keyword.value=value;clearTimeout(searchTimer);searchTimer=setTimeout(()=>void loadOptions(),250)}
function choose(id:number){target.value=id;chosen.value=options.rows.value.find(row=>row.id===id)||(chosen.value?.id===id?chosen.value:undefined)}
async function created(row:SampleLocationOption){editorOpen.value=false;site.value=row.site;keyword.value='';await options.reload();await loadOptions();chosen.value=row;target.value=row.id}
watch(site,()=>{if(!unknown.value){target.value=undefined;chosen.value=undefined;sourceLocationId.value=undefined;keyword.value='';void loadOptions()}})
watch(()=>user.token,()=>emit('close'))
onMounted(async()=>{try{const {content:actual}=await querySampleIdentities({ids,details:true});if(!disposed&&auth===user.token){rows.value=actual;ready.value=true}}catch(e){if(!disposed)error.value=partnerError(e)}finally{loading.value=false}if(!disposed)void loadOptions()})
onBeforeUnmount(()=>{disposed=true;clearTimeout(searchTimer)})
async function close(){if(busy.value||unknown.value)return;if(target.value||reason.value)try{await ElMessageBox.confirm(uiText('放弃本次尚未保存的位置设置？'),uiText('保留填写'),{confirmButtonText:uiText('放弃'),cancelButtonText:uiText('继续填写')})}catch{return}emit('close')}
async function save(){
 if(!canWrite.value||(!unknown.value&&!ready.value))return
 if(!unknown.value&&mode.value==='clear_code'&&!sourceLocationId.value){error.value=uiText('请选择要清除的库位');return}
 if(!unknown.value&&mode.value==='all_onhand'&&!target.value){error.value=uiText('请选择一个已登记的目标位置，或新增后使用');return}
 const result=await submit(()=>({requestId,mode:mode.value,site:site.value,...(mode.value==='clear_code'&&sourceLocationId.value?{sourceLocationId:sourceLocationId.value}:{}),...(mode.value==='all_onhand'?{targetLocationId:target.value}:{}),...(date.value?{date:date.value}:{}),...(reason.value.trim()?{reason:reason.value.trim()}:{}),items:rows.value.map(row=>({sampleIdentityId:row.sampleIdentityId,version:row.version}))}),setSampleLocations)
 if(result&&!disposed&&auth===user.token){ElMessage.success(uiText('位置设置已保存：移动{moved}件，原位未变{unchanged}件',{moved:result.totals.movedQty,unchanged:result.totals.unchangedQty}));emit('saved')}
}
</script>
<template>
 <el-dialog class="sample-location-set-dialog" :model-value="true" :title="uiText(samples.length>1?'批量设置位置':'设置位置')" width="min(780px,96vw)" append-to-body :close-on-click-modal="false" :before-close="close"><section class="location-set-content" v-loading="loading">
  <p>{{uiText('默认办理所选站点的全部在库实物；借出和在途不变，精细数量仍从原移库入口办理。')}}</p>
  <el-form label-position="top" :disabled="busy||unknown||!canWrite">
   <div class="set-target"><el-form-item :label="uiText('站点')"><el-select v-model="site" :disabled="access.localOnly"><el-option v-for="value in sampleLocationSites.filter(value=>!access.localOnly||value.value==='MX')" :key="value.value" :value="value.value" :label="uiText(value.label)"/></el-select></el-form-item><el-form-item v-if="mode==='all_onhand'" class="target-picker" :label="uiText('目标位置（所有选中样品共用）')" required><el-select :model-value="target" filterable remote :remote-method="search" :loading="options.loading.value" :aria-label="uiText('统一目标位置')" :placeholder="uiText('搜索位置码或说明')" @update:model-value="choose"><el-option v-if="chosen&&!targetOptions.some(row=>row.id===chosen?.id)" :value="chosen.id" :label="sampleLocationLabel(chosen)"/><el-option v-for="row in targetOptions" :key="row.id" :value="row.id" :label="sampleLocationLabel(row)"/></el-select></el-form-item><el-button v-if="mode==='all_onhand'" @click="editorOpen=true">{{uiText('新增位置并使用')}}</el-button><el-form-item v-if="mode==='clear_code'" class="target-picker" :label="uiText('清除的库位')"><el-select v-model="sourceLocationId" :aria-label="uiText('清除的库位')"><el-option v-for="p in sources" :key="p.locationId" :value="p.locationId!" :label="sampleLocationLabel(p)"/></el-select></el-form-item></div><small v-if="options.more.value">{{uiText('显示前50项，请输入位置码或说明继续搜索')}}</small><el-alert v-if="options.error.value" :title="options.error.value" type="error" :closable="false"><el-button link @click="options.reload">{{uiText('重试')}}</el-button></el-alert>
   <el-alert v-if="mode==='clear_code'" :title="uiText('明确清除所选站点的库位关联，样品回到本站未设库位；样品码和数量保留。')" type="info" :closable="false"/>
   <details class="set-extra"><summary>{{uiText('日期和说明（选填，默认今天）')}}</summary><el-form-item :label="uiText('实际日期')"><el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD"/></el-form-item><el-form-item :label="uiText('本次说明')"><el-input v-model="reason" type="textarea" autosize/></el-form-item></details>
  </el-form>
  <div class="set-rows"><article v-for="row in rows" :key="row.sampleIdentityId"><div><strong>{{row.sampleCode}} · {{row.productName||row.name}}</strong><small>{{row.phoneName||row.specName}}</small></div><div><strong>{{uiText('本次在库 ')}}{{sitePositions(row).reduce((qty,p)=>qty+p.onhand,0)}}{{uiText(' 件')}}</strong><small>{{uiText('其中留样 ')}}{{sitePositions(row).reduce((qty,p)=>qty+p.retained,0)}} · {{uiText('损坏 ')}}{{sitePositions(row).reduce((qty,p)=>qty+p.damaged,0)}}</small></div></article></div><p class="set-total">{{uiText('共{count}种样品，在库{qty}件；以保存时实际在库数量办理。',{count:rows.length,qty:onhand})}}</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
 </section><template #footer><el-button v-if="mode==='all_onhand'&&sources.length" link :disabled="busy||unknown" @click="mode='clear_code'">{{uiText('清除库位')}}</el-button><el-button v-else-if="mode==='clear_code'" link :disabled="busy||unknown" @click="mode='all_onhand'">{{uiText('返回设置位置')}}</el-button><el-button :disabled="busy||unknown" @click="close">{{uiText('取消')}}</el-button><el-button type="primary" :loading="busy" :disabled="!canWrite||(!unknown&&(!ready||!onhand))" @click="save">{{uiText(unknown?'核对本次保存结果':mode==='clear_code'?'确认清除库位':'确认设置位置')}}</el-button></template><SampleLocationEditor v-if="editorOpen" :initial-site="site" use-after-save @close="editorOpen=false" @saved="created"/></el-dialog>
</template>
<style scoped>.location-set-content{min-width:0;font-size:12px;line-height:1.6}.location-set-content>p{margin:0 0 10px;color:#667d97}.set-target{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.set-target>.el-form-item:first-child{width:115px;flex:none}.set-target .target-picker{flex:1;min-width:220px}.set-target>.el-button{margin-top:8px}.set-target .el-select{width:100%}.set-extra{margin-bottom:10px;color:#667d97}.set-extra summary{cursor:pointer}.set-rows{max-height:36vh;overflow:auto;border:1px solid #e0e7f0}.set-rows article{display:flex;justify-content:space-between;gap:12px;padding:7px 10px;border-bottom:1px solid #e8edf4}.set-rows article>div{min-width:0;overflow-wrap:anywhere}.set-rows small{display:block;color:#74879e}.set-rows strong{font-weight:600;color:#35516f}.set-total{margin-top:7px!important}:global(.sample-location-set-dialog){display:flex;flex-direction:column;max-height:calc(100dvh - 40px);margin-block:20px;overflow:hidden}:global(.sample-location-set-dialog>.el-dialog__body){min-height:0;overflow:auto}
</style>
