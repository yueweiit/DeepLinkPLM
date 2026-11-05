<script setup lang="ts">
import {computed,ref} from 'vue'
import {t as uiText} from '@/locales'
import SharedCompatiblePhoneSelector from '@/views/mold/components/SharedCompatiblePhoneSelector.vue'
import ManufacturingRelationPicker from '@/views/mold/components/ManufacturingRelationPicker.vue'
import MoldScopeEditor from './MoldScopeEditor.vue'
import type {EngineeringPlan,EngineeringSpecification} from '@/api/modules/moldApplications'
import type {ManufacturingRelation} from '@/api/modules/manufacturing'
const props=defineProps<{modelValue:EngineeringPlan;productLineId:number|null;disabled?:boolean}>()
const emit=defineEmits<{'update:modelValue':[EngineeringPlan];relationSelected:[ManufacturingRelation]}>()
const selecting=ref<string>(),selectedRelations=ref<Record<string,ManufacturingRelation>>({})
const phoneIds=computed(()=>[...new Set(props.modelValue.specifications.flatMap(row=>row.phoneModelIds))])
const requiresExistingRelation=computed(()=>props.modelValue.handling==='reuse'||props.modelValue.handling==='new_set')
function update(patch:Partial<EngineeringPlan>){emit('update:modelValue',{...props.modelValue,...patch})}
function change(key:string,patch:Partial<EngineeringSpecification>){update({specifications:props.modelValue.specifications.map(row=>row.key===key?{...row,...patch}:row)})}
function adopt(row:ManufacturingRelation){if(!selecting.value)return;selectedRelations.value[selecting.value]=row;change(selecting.value,{holeType:row.holeType,structureDescription:row.structureDescription,manufacturingRelationId:row.relationId||row.id,manufacturingRelationVersion:row.relationVersion||row.version});emit('relationSelected',row);selecting.value=undefined}
</script>
<template>
<section class="engineering-plan" aria-label="本次工程方案">
  <h3>{{uiText('本次适用规格与办理方式')}}</h3>
  <el-radio-group :model-value="modelValue.handling" :disabled="disabled" @update:model-value="update({handling:$event as EngineeringPlan['handling']})"><el-radio value="pending">{{uiText('待工程确认')}}</el-radio><el-radio value="new">{{uiText('新开')}}</el-radio><el-radio value="new_set">{{uiText('加开一套')}}</el-radio><el-radio value="reuse">{{uiText('沿用已有')}}</el-radio></el-radio-group>
  <p>{{uiText('共模不等于共用成品。手机资料编码保留，工厂使用已确认制造物料的原编码。')}}</p>
  <MoldScopeEditor :model-value="modelValue" :disabled="disabled" :multiple-phones="!requiresExistingRelation" manage-specifications :phone-hint="requiresExistingRelation?'每行核对一款手机；同孔型其他手机可复制本行分别确认。':'尚未确定可后补；选择手机不会自动改写生产编码。'" @update:model-value="emit('update:modelValue',$event)">
    <template #reference="{row}"><div v-if="requiresExistingRelation" class="relation-choice"><el-button :disabled="disabled||!productLineId||row.phoneModelIds.length!==1||!row.holeType" @click="selecting=row.key">{{uiText('选择已确认的生产与模具关系')}}</el-button><span v-if="row.manufacturingRelationId">{{uiText('关系')}} #{{row.manufacturingRelationId}} · V{{row.manufacturingRelationVersion}}<template v-if="selectedRelations[row.key]"> · {{selectedRelations[row.key]!.sharedMaterial?uiText('共用生产物料'):uiText('仅共模，不共用成品')}} · {{uiText('制造编码')}} {{selectedRelations[row.key]!.manufacturingPhoneCode||uiText('独立物料待确认')}}</template></span><small>{{uiText('未确认或孔型不符不能沿用；不同实物不合并库存。')}}</small></div></template>
  </MoldScopeEditor>
  <el-form-item v-if="modelValue.handling==='new'||modelValue.handling==='new_set'" :label="uiText('正式建档使用的编码基准手机')"><SharedCompatiblePhoneSelector :model-value="modelValue.codePhoneModelId?[modelValue.codePhoneModelId]:[]" :multiple="false" :disabled="disabled" @update:model-value="update({codePhoneModelId:$event[0]})"/><small>{{uiText('可在工程建档前明确；不是客户型号的替代码，不改变原模具刻码。')}}</small><small v-if="modelValue.codePhoneModelId&&!phoneIds.includes(modelValue.codePhoneModelId)">{{uiText('编码基准须属于已确认的适用手机范围。')}}</small></el-form-item>
  <p v-if="modelValue.handling==='pending'">{{uiText('审批通过也不会直接新建模具或生成正式编码，须先完成工程判断。')}}</p>
  <p v-if="modelValue.handling==='reuse'">{{uiText('确认沿用不发起新开模审批；项目正式采用仍按原模具设计阶段办理。')}}</p>
  <ManufacturingRelationPicker v-if="selecting&&productLineId" :product-line-id="productLineId" :require-shared-material="modelValue.handling==='new_set'" :phone-model-id="modelValue.specifications.find(row=>row.key===selecting)?.phoneModelIds[0]" :hole-type="modelValue.specifications.find(row=>row.key===selecting)?.holeType" @close="selecting=undefined" @selected="adopt"/>
</section>
</template>
<style scoped>
.engineering-plan{font-size:12px;margin:10px 0;padding:12px;border:1px solid var(--el-border-color-light);border-radius:4px;min-width:0}.engineering-plan h3{font-size:14px;margin:0 0 10px}.engineering-plan p,.engineering-plan small{color:var(--el-text-color-secondary);line-height:1.6}.engineering-plan small{display:block}.relation-choice{display:flex;flex-wrap:wrap;gap:8px;overflow-wrap:anywhere}.relation-choice small{width:100%}.engineering-plan :deep(.el-select){width:100%}.engineering-plan :deep(.el-radio-group){display:flex;flex-wrap:wrap}.engineering-plan :deep(.el-radio){margin-right:18px}.engineering-plan :deep(.el-radio__label){font-size:12px}@media(max-width:700px){.engineering-plan{padding:8px}}
</style>
