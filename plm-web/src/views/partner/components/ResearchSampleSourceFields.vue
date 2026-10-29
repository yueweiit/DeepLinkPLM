<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import PhoneObjectIdSelect from '@/components/PhoneObjectSelect/IdSelect.vue'
import SharedCompatiblePhoneSelector from '@/views/mold/components/SharedCompatiblePhoneSelector.vue'
import {researchSampleSources} from '@/api/modules/samples'
import {partnerError} from '@/utils/partnerError'
import {sampleKinds} from '@/utils/sampleDomains'
import {useUserStore} from '@/stores/user'
import {useRouter} from 'vue-router'
import type {BusinessData} from '@/types/partner'
import type {PhoneObjectOption} from '@/components/PhoneObjectSelect/types'
import type {ResearchSampleChoices} from '@/types/samples'
import {launchStage} from '@/constants/productResearch'

const props=defineProps<{modelValue:BusinessData;domain:'model'|'research'}>()
const emit=defineEmits<{'update:modelValue':[BusinessData];change:[]}>()
const user=useUserStore(),router=useRouter(),products=ref<ResearchSampleChoices['products']>([]),projects=ref<ResearchSampleChoices['projects']>([]),choices=ref<ResearchSampleChoices>({}),loading=ref(false),error=ref('')
let epoch=0
const kinds=['prototype','pc','tpu','case'] as const
const modelReady=computed(()=>!!props.modelValue.modelProductId&&!!props.modelValue.phoneModelId)
const projectOptions=computed<PhoneObjectOption[]>(()=>(projects.value||[]).map(p=>({type:'product',id:p.id,name:p.name,code:p.code,phoneModels:p.phoneModels||[]})))
const setOptions=computed<PhoneObjectOption[]>(()=>(choices.value.sets||[]).map(s=>({type:'mold_set',id:s.id,name:`${s.moldName||s.name} · 第 ${s.setNo} 套`,code:s.engravedCode||`MS-${s.id}`,phoneModels:s.phoneModels||[]})))
function set(patch:BusinessData){emit('update:modelValue',{...props.modelValue,...patch,...(props.domain==='model'?{sourceType:'model',sampleKind:undefined}:{})});emit('change')}
function changeKind(kind:string){set({sampleKind:kind,sourceType:'self',roundId:undefined,nodeAuditId:undefined,moldSetId:undefined})}
function changeProduct(id:number|null){set({modelProductId:id||undefined,projectId:undefined,moldSetId:undefined,trialBatch:''})}
function changePhone(ids:number[]){set({phoneModelIds:ids,phoneModelId:ids[0],projectId:undefined,moldSetId:undefined,trialBatch:''})}
function changeProject(id:number|null){set({projectId:id||undefined,roundId:undefined,nodeAuditId:undefined,moldSetId:undefined,trialBatch:''})}
async function load(){
 const serial=++epoch,token=user.token,domain=props.domain,project=Number(props.modelValue.projectId)||undefined
 const context=domain==='model'?{modelProductId:Number(props.modelValue.modelProductId)||undefined,phoneModelId:Number(props.modelValue.phoneModelId)||undefined}:undefined
 loading.value=true;error.value='';choices.value={};projects.value=[];products.value=[]
 try{
  const [list,source]=await Promise.all([researchSampleSources(domain,undefined,context),project?researchSampleSources(domain,project,context):Promise.resolve<ResearchSampleChoices>({})])
  if(serial!==epoch||token!==user.token)return
  products.value=list.products||[];projects.value=list.projects||[];choices.value={...list,...source}
  if(domain==='model'&&source.project){
   const patch:BusinessData={}
   if(!props.modelValue.modelProductId&&source.project.modelProductId)patch.modelProductId=source.project.modelProductId
   const ids=[...new Set((source.project.phoneModels||[]).map(p=>p.phoneModelId).filter((id):id is number=>!!id))]
   if(!props.modelValue.phoneModelId&&ids.length===1){patch.phoneModelId=ids[0];patch.phoneModelIds=ids}
   if(Object.keys(patch).length)set(patch)
  }
 }catch(e){if(serial===epoch&&token===user.token)error.value=partnerError(e)}finally{if(serial===epoch)loading.value=false}
}
watch([()=>props.domain,()=>props.modelValue.projectId,()=>props.modelValue.modelProductId,()=>props.modelValue.phoneModelId,()=>user.token],()=>void load(),{immediate:true})
onBeforeUnmount(()=>{epoch++})
</script>
<template>
 <div class="research-sample-source" v-loading="loading">
  <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load">重新加载来源</el-button></el-alert>
  <template v-if="domain==='model'">
   <el-form-item label="所属产品 *"><el-select :model-value="modelValue.modelProductId" filterable clearable aria-label="型号样品所属产品" placeholder="请选择产品，如亮甲 2.0、骑士、圣殿" @change="changeProduct"><el-option v-for="product in products" :key="product.id" :value="product.id" :label="product.name+(product.salesStatus==='off_sale'?'（停售）':product.salesStatus==='unconfirmed'?'（在售状态未确认）':'')"/></el-select></el-form-item>
   <p v-if="!loading&&!error&&!products?.length" class="source-hint">没有可查看的产品资料，请在基础资料的产品目录中补齐产品或由管理员核对资料权限。<el-button link @click="load">刷新来源</el-button></p>
   <el-form-item label="实际手机型号 *"><SharedCompatiblePhoneSelector :model-value="modelValue.phoneModelIds||[]" :multiple="false" :disabled="!modelValue.modelProductId" aria-label="样品手机型号" @update:model-value="changePhone"/></el-form-item>
   <el-form-item label="实际样品来源说明 *"><el-input :model-value="modelValue.provenance" type="textarea" autosize placeholder="填写制作方、收样单或采购／借入依据" @update:model-value="set({provenance:$event})"/></el-form-item>
   <el-form-item label="原物料／资产编号（选填）"><el-input :model-value="modelValue.assetNumber" placeholder="保留已有编号，不生成或改写物料库存" @update:model-value="set({assetNumber:$event})"/></el-form-item>
  </template>
  <el-form-item v-else label="样品类别 *"><el-select :model-value="modelValue.sampleKind" aria-label="样品类别" @change="changeKind"><el-option v-for="kind in kinds" :key="kind" :value="kind" :label="sampleKinds[kind]"/></el-select></el-form-item>
  <el-form-item :label="domain==='model'?'来源型号项目（选填）':'来源研发项目 *'"><PhoneObjectIdSelect :model-value="modelValue.projectId" :options="projectOptions" type="product" :label="domain==='model'?'本产品与手机的型号项目':'新品研发项目'" :disabled="loading||(domain==='model'&&(!modelReady||choices.canReadProjects===false))" @update:model-value="changeProject"/></el-form-item>
  <template v-if="domain==='model'">
   <p v-if="!loading&&!error&&choices.canReadProjects===false" class="source-hint">当前账号无项目读取权限，可按产品和手机登记实际样品；需要关联来源项目时，请管理员补齐项目权限。</p>
   <p v-else-if="modelReady&&!loading&&!error&&!projectOptions.length" class="source-hint">此产品与手机尚无明确关联的型号项目，可先登记实际收样；已有项目请在项目中心补齐真实关系后刷新。<el-button link @click="load">刷新来源</el-button></p>
   <el-form-item v-if="modelValue.projectId" label="来源具体模具套（选填）"><PhoneObjectIdSelect :model-value="modelValue.moldSetId" :options="setOptions" type="mold_set" label="本项目具体模具套" :disabled="loading||choices.canReadSets===false" @update:model-value="set({moldSetId:$event,trialBatch:''})"/></el-form-item>
   <p v-if="modelValue.projectId&&!loading&&!error&&choices.canReadSets===false" class="source-hint">当前账号缺少模具／排程读取权限，请在账号授权中补齐；本页不显示未授权套及数量。</p>
   <p v-else-if="modelValue.projectId&&!loading&&!error&&!setOptions.length" class="source-hint">本项目尚未明确关联具体套，主档关联不能代替具体套。<a :href="router.resolve({path:'/projects',query:{productId:modelValue.projectId}}).href" target="_blank" rel="noopener">查看本项目申请与模具关联</a><el-button link @click="load">刷新来源</el-button></p>
   <el-form-item v-if="modelValue.moldSetId" label="实际试模批次／轮次 *"><el-input :model-value="modelValue.trialBatch" placeholder="如 T0、T1 或工厂实际批次" @update:model-value="set({trialBatch:$event})"/></el-form-item>
  </template>
  <template v-if="domain==='research'">
   <el-form-item label="已记录实际收样的研发轮次 *"><el-select :model-value="modelValue.roundId" :disabled="!modelValue.projectId||loading" aria-label="实际研发样品轮次" @change="set({roundId:$event,sourceType:'self'})"><el-option v-for="round in choices.rounds||[]" :key="round.id" :value="round.id" :label="`S${round.no} · ${round.title} · ${round.date}`"/></el-select></el-form-item>
   <p class="source-hint">图纸、照片及采用版本沿用原轮次。有真实日期、制作方和机型范围即可登记实物；验证待完成也可收样，结果仍在原研发阶段办理。</p>
   <p v-if="modelValue.projectId&&!loading&&!error&&!(choices.rounds||[]).length" class="source-hint">本项目还没有有效的实际收样轮次。<a :href="router.resolve({path:'/product-research',query:{productId:modelValue.projectId,step:launchStage(3).key}}).href" target="_blank" rel="noopener">打开本项目打样记录</a><el-button link @click="load">刷新来源</el-button></p>
  </template>
  <el-form-item label="样品版本／规格补充"><el-input :model-value="modelValue.sampleVersion" placeholder="保留本次实际版本或配置" @update:model-value="set({sampleVersion:$event})"/></el-form-item>
  <el-form-item label="用途说明"><el-input :model-value="modelValue.purpose" type="textarea" autosize @update:model-value="set({purpose:$event})"/></el-form-item>
 </div>
</template>
<style scoped>.research-sample-source{min-width:0}.research-sample-source :deep(.el-select){width:100%}.source-hint{font-size:12px;color:var(--el-text-color-secondary);line-height:1.6;margin:0 0 10px;overflow-wrap:anywhere}</style>
