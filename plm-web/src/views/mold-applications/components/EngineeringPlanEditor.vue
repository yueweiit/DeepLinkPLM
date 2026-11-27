<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {t as uiText} from '@/locales'
import SharedCompatiblePhoneSelector from '@/views/mold/components/SharedCompatiblePhoneSelector.vue'
import ManufacturingRelationPicker from '@/views/mold/components/ManufacturingRelationPicker.vue'
import MoldShareMappingPicker from '@/views/mold-sharing/components/MoldShareMappingPicker.vue'
import SharedExistingMoldPicker from '@/views/mold-sharing/components/SharedExistingMoldPicker.vue'
import {getMoldShareMappings,resolveMoldSharing,type MoldShareMapping} from '@/api/modules/moldSharing'
import {ElMessage} from 'element-plus'
import MoldScopeEditor from './MoldScopeEditor.vue'
import type {EngineeringPlan,EngineeringSpecification} from '@/api/modules/moldApplications'
import type {ManufacturingRelation} from '@/api/modules/manufacturing'
const props=defineProps<{modelValue:EngineeringPlan;productLineId:number|null;disabled?:boolean}>()
const emit=defineEmits<{'update:modelValue':[EngineeringPlan];relationSelected:[ManufacturingRelation]}>()
const selecting=ref<string>(),selectedRelations=ref<Record<string,ManufacturingRelation>>({})
const phoneIds=computed(()=>[...new Set(props.modelValue.specifications.flatMap(row=>row.phoneModelIds))])
const requiresExistingRelation=computed(()=>['reuse','new_set','shared_existing'].includes(props.modelValue.handling))
const moldOnly=computed(()=>props.modelValue.identityMode==='mold_only')
const selectingSpec=computed(()=>props.modelValue.specifications.find(row=>row.key===selecting.value))
const resolving=ref(false)
const mappingExists=ref(false)
watch(()=>JSON.stringify([props.productLineId,props.modelValue.handling,props.modelValue.specifications]),async(_,__,onCleanup)=>{
 let cancelled=false;onCleanup(()=>{cancelled=true});mappingExists.value=false
 if(!props.productLineId||props.modelValue.codeRuleVersion!==2||props.modelValue.handling!=='new')return
 try{const matches=await Promise.all(props.modelValue.specifications.flatMap(spec=>spec.phoneModelIds.map(sourcePhoneModelId=>getMoldShareMappings({productLineId:props.productLineId!,sourcePhoneModelId,holeType:spec.holeType,structureDescription:spec.structureDescription,status:'active',page:1,size:1}))));if(!cancelled)mappingExists.value=matches.some(result=>result.totalElements>0)}catch{ /* Reservation also enforces this check. */ }
},{immediate:true})
function update(patch:Partial<EngineeringPlan>){emit('update:modelValue',{...props.modelValue,...patch})}
function mode(value:unknown){update({identityMode:value==='mold_only'?'mold_only':undefined,specifications:props.modelValue.specifications.map(row=>({...row,moldSharing:undefined,manufacturingRelationId:undefined,manufacturingRelationVersion:undefined}))})}
function change(key:string,patch:Partial<EngineeringSpecification>){update({specifications:props.modelValue.specifications.map(row=>row.key===key?{...row,...patch}:row)})}
function adopt(row:ManufacturingRelation){if(!selecting.value)return;selectedRelations.value[selecting.value]=row;change(selecting.value,{holeType:row.holeType,structureDescription:row.structureDescription,manufacturingRelationId:row.relationId||row.id,manufacturingRelationVersion:row.relationVersion||row.version});emit('relationSelected',row);selecting.value=undefined}
async function adoptMappings(rows:MoldShareMapping[]){
 if(!selectingSpec.value||!rows.length||!props.productLineId||resolving.value)return
 const key=selectingSpec.value.key,context=JSON.stringify([props.productLineId,props.modelValue]),plan:EngineeringPlan={...props.modelValue,specifications:[{...selectingSpec.value,moldSharing:{referencePhoneModelId:rows[0]!.referencePhoneModelId,mappings:rows.map(row=>({mappingId:row.id,version:row.version,materialCode:row.materialCode}))}}]}
 resolving.value=true
 try{const result=await resolveMoldSharing(props.productLineId,plan);const row=result[0];if(!row)throw Error('共模解析未返回实物套');if(context!==JSON.stringify([props.productLineId,props.modelValue]))return;change(key,{moldSharing:plan.specifications[0]!.moldSharing,manufacturingRelationId:undefined,manufacturingRelationVersion:undefined});selectedRelations.value[key]=row;emit('relationSelected',row);selecting.value=undefined}catch(e){ElMessage.error(uiText(e instanceof Error?e.message:'共模核对失败'))}finally{resolving.value=false}
}
async function adoptSharedEntity(relation:ManufacturingRelation){
 if(!selectingSpec.value||!props.productLineId||!relation.parts?.length||resolving.value)return
 const key=selectingSpec.value.key,context=JSON.stringify([props.productLineId,props.modelValue]),mappings=relation.parts.map(part=>({mappingId:relation.relationId,version:relation.relationVersion,materialCode:part.partType==='TPU'?'10':'20'}))
 const plan:EngineeringPlan={...props.modelValue,identityMode:'mold_only',specifications:[{...selectingSpec.value,moldSharing:{referencePhoneModelId:relation.phoneModelId,mappings}}]};resolving.value=true
 try{const result=await resolveMoldSharing(props.productLineId,plan);const row=result[0];if(!row)throw Error('实体模具核对未返回结果');if(context!==JSON.stringify([props.productLineId,props.modelValue]))return;change(key,{moldSharing:plan.specifications[0]!.moldSharing,manufacturingRelationId:undefined,manufacturingRelationVersion:undefined});selectedRelations.value[key]=row;selecting.value=undefined}catch(e){ElMessage.error(uiText(e instanceof Error?e.message:'实体模具核对失败'))}finally{resolving.value=false}
}
</script>
<template>
<section class="engineering-plan" aria-label="本次工程方案">
  <h3>{{uiText('本次适用规格与办理方式')}}</h3>
  <el-radio-group :model-value="modelValue.handling" :disabled="disabled" @update:model-value="update({handling:$event as EngineeringPlan['handling']})"><el-radio value="pending">{{uiText('待工程确认')}}</el-radio><el-radio value="new">{{uiText('新开')}}</el-radio><el-radio value="new_set">{{uiText('加开一套')}}</el-radio><el-radio value="reuse">{{uiText('沿用已有（不送审）')}}</el-radio></el-radio-group>
  <p>{{uiText(modelValue.handling==='shared_existing'?'审批通过并经工程确认后生成目标机型映射码；生产流转使用该映射码并追溯至同一实体主码。':'选择“沿用已有”或“共模”时，不会新增模具实物套。系统仅建立当前机型与已有模具的映射关系；生产时使用当前机型的映射模具编码，但实际使用的是同一套模具。手机编码和成品库存仍按各机型独立管理。')}}</p>
  <el-alert v-if="mappingExists" type="warning" :closable="false" :title="uiText('此规格已有共模映射，请选择沿用已有；确需新增实物请选加开一套并填写原因')"/>
  <el-radio-group v-if="modelValue.codeRuleVersion===2&&requiresExistingRelation" :model-value="moldOnly?'mold_only':'manufacturing'" :disabled="disabled" @update:model-value="mode"><el-radio value="mold_only">{{uiText('仅共模，成品保持自身机型')}}</el-radio><el-radio value="manufacturing">{{uiText('原生产物料关联流程')}}</el-radio></el-radio-group>
  <MoldScopeEditor :model-value="modelValue" :disabled="disabled" :multiple-phones="!requiresExistingRelation" manage-specifications :phone-hint="requiresExistingRelation?'每行核对一款手机；同孔型其他手机可复制本行分别确认。':'尚未确定可后补；选择手机不会自动改写生产编码。'" @update:model-value="emit('update:modelValue',$event)">
    <template #reference="{row}"><div v-if="requiresExistingRelation" class="relation-choice"><el-button :loading="resolving" :disabled="disabled||!productLineId||row.phoneModelIds.length!==1||!row.holeType" @click="selecting=row.key">{{uiText(moldOnly?'选择共用模具的其他机型':'选择已确认的生产与模具关系')}}</el-button><span v-if="moldOnly&&row.moldSharing">{{uiText('基准机型')}} #{{row.moldSharing.referencePhoneModelId}} · {{row.moldSharing.mappings.map(m=>`${m.materialCode} / #${m.mappingId} / V${m.version}`).join('；')}}<template v-if="selectedRelations[row.key]"> · {{selectedRelations[row.key]!.parts?.map(p=>p.code).join(' / ')}}</template></span><span v-else-if="row.manufacturingRelationId">{{uiText('关系')}} #{{row.manufacturingRelationId}} · V{{row.manufacturingRelationVersion}}</span><small>{{uiText('未确认或孔型不符不能沿用；不同实物不合并库存。')}}</small></div></template>
  </MoldScopeEditor>
  <el-form-item v-if="['new','new_set','shared_existing'].includes(modelValue.handling)" :label="uiText(modelValue.handling==='shared_existing'?'本次审批标准手机':modelValue.codeRuleVersion===2?'本次建档的自身手机':'正式建档使用的编码基准手机')"><SharedCompatiblePhoneSelector :model-value="modelValue.codePhoneModelId?[modelValue.codePhoneModelId]:[]" :multiple="false" :disabled="disabled" @update:model-value="update({codePhoneModelId:$event[0]})"/><small>{{uiText(modelValue.handling==='shared_existing'?'此项用于本次申请与钉钉表单，不预留新模具编码；映射码在工程确认后生成。':'成品使用此手机自身编码；共模基准在上方另选。')}}</small><small v-if="modelValue.codePhoneModelId&&!phoneIds.includes(modelValue.codePhoneModelId)">{{uiText('编码基准须属于已确认的适用手机范围。')}}</small></el-form-item>
  <p v-if="modelValue.handling==='pending'">{{uiText('审批通过也不会直接新建模具或生成正式编码，须先完成工程判断。')}}</p>
  <p v-if="modelValue.handling==='reuse'">{{uiText('确认沿用不发起新审批；新品正式采用在模具设计阶段办理。')}}</p>
  <SharedExistingMoldPicker v-if="selecting&&productLineId&&modelValue.handling==='shared_existing'&&selectingSpec?.phoneModelIds[0]" :product-line-id="productLineId" :target-phone-model-id="selectingSpec.phoneModelIds[0]" :hole-type="selectingSpec.holeType" :structure-description="selectingSpec.structureDescription" @close="selecting=undefined" @selected="adoptSharedEntity"/>
  <MoldShareMappingPicker v-else-if="selecting&&productLineId&&moldOnly&&selectingSpec?.phoneModelIds[0]" :product-line-id="productLineId" :source-phone-model-id="selectingSpec.phoneModelIds[0]" :hole-type="selectingSpec.holeType" :structure-description="selectingSpec.structureDescription" @close="selecting=undefined" @selected="adoptMappings"/>
  <ManufacturingRelationPicker v-else-if="selecting&&productLineId" :product-line-id="productLineId" :require-shared-material="modelValue.handling==='new_set'" :phone-model-id="selectingSpec?.phoneModelIds[0]" :hole-type="selectingSpec?.holeType" @close="selecting=undefined" @selected="adopt"/>
</section>
</template>
<style scoped>
.engineering-plan{font-size:12px;margin:10px 0;padding:12px;border:1px solid var(--el-border-color-light);border-radius:4px;min-width:0}.engineering-plan h3{font-size:14px;margin:0 0 10px}.engineering-plan p,.engineering-plan small{color:var(--el-text-color-secondary);line-height:1.6}.engineering-plan small{display:block}.relation-choice{display:flex;flex-wrap:wrap;gap:8px;overflow-wrap:anywhere}.relation-choice small{width:100%}.engineering-plan :deep(.el-select){width:100%}.engineering-plan :deep(.el-radio-group){display:flex;flex-wrap:wrap}.engineering-plan :deep(.el-radio){margin-right:18px}.engineering-plan :deep(.el-radio__label){font-size:12px}@media(max-width:700px){.engineering-plan{padding:8px}}
</style>
