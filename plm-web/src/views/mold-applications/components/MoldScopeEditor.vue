<script setup lang="ts">
import {t as uiText} from '@/locales'
import SharedCompatiblePhoneSelector from '@/views/mold/components/SharedCompatiblePhoneSelector.vue'
import type {EngineeringPlan,EngineeringSpecification} from '@/api/modules/moldApplications'
import {emptyEngineeringSpecification,holeTypeNames} from '@/utils/engineeringPlan'
const props=withDefaults(defineProps<{modelValue:EngineeringPlan;disabled?:boolean;multiplePhones?:boolean;manageSpecifications?:boolean;allowCreate?:boolean;phoneHint?:string}>(),{multiplePhones:true})
const emit=defineEmits<{'update:modelValue':[EngineeringPlan]}>()
function specifications(rows:EngineeringSpecification[]){emit('update:modelValue',{...props.modelValue,specifications:rows})}
function change(key:string,patch:Partial<EngineeringSpecification>){specifications(props.modelValue.specifications.map(row=>row.key===key?{...row,...patch,manufacturingRelationId:undefined,manufacturingRelationVersion:undefined}:row))}
function add(source?:EngineeringSpecification){specifications([...props.modelValue.specifications,source?{...source,key:crypto.randomUUID(),phoneModelIds:[],manufacturingRelationId:undefined,manufacturingRelationVersion:undefined}:emptyEngineeringSpecification()])}
</script>
<template>
  <div class="mold-scope">
    <article v-for="(row,index) in modelValue.specifications" :key="row.key" :data-specification-key="row.key">
      <header v-if="manageSpecifications||modelValue.specifications.length>1"><strong>{{uiText('规格')}} {{index+1}}</strong><template v-if="manageSpecifications"><el-button link :disabled="disabled" @click="add(row)">{{uiText('复制孔型，另选手机')}}</el-button><el-button v-if="modelValue.specifications.length>1" link type="danger" :disabled="disabled" @click="specifications(modelValue.specifications.filter(item=>item.key!==row.key))">{{uiText('移除')}}</el-button></template></header>
      <div class="specification-grid">
        <el-form-item :label="uiText('孔型')" required><el-select :model-value="row.holeType" :disabled="disabled" :aria-label="'规格'+(index+1)+'孔型'" @update:model-value="change(row.key,{holeType:$event})"><el-option v-for="(label,value) in holeTypeNames" :key="value" :value="value" :label="uiText(label)"/></el-select></el-form-item>
        <el-form-item :label="uiText('结构说明')" :required="row.holeType==='other'"><el-input :model-value="row.structureDescription" :disabled="disabled" maxlength="1000" :aria-label="'规格'+(index+1)+'结构说明'" @update:model-value="change(row.key,{structureDescription:$event})"/></el-form-item>
        <el-form-item class="full" :label="uiText('实际适用手机')"><SharedCompatiblePhoneSelector :model-value="row.phoneModelIds" :multiple="multiplePhones" :allow-create="allowCreate" :disabled="disabled" @update:model-value="change(row.key,{phoneModelIds:$event})"/><small>{{uiText(phoneHint||'选一款表示本次适用一款；选多款表示这几款本次共模。未建档的型号可直接新增。')}}</small></el-form-item>
        <div class="full"><slot name="reference" :row="row"/></div>
      </div>
    </article>
    <el-button v-if="manageSpecifications" :disabled="disabled" @click="add()">＋{{uiText('添加适用规格')}}</el-button>
  </div>
</template>
<style scoped>
.mold-scope{min-width:0;font-size:12px}.mold-scope article{min-width:0}.mold-scope article+article{margin-top:8px;padding-top:10px;border-top:1px solid var(--el-border-color-light)}.mold-scope header{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:8px}.specification-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 12px}.full{grid-column:1/-1;min-width:0}.mold-scope small{display:block;font-size:12px;color:var(--el-text-color-secondary);line-height:1.6}.mold-scope :deep(.el-select){width:100%}@media(max-width:700px){.specification-grid{grid-template-columns:1fr}}
</style>
