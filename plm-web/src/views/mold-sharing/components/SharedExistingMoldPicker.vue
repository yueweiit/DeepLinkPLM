<script setup lang="ts">
import {ref,watch} from 'vue'
import {t as uiText} from '@/locales'
import SharedCompatiblePhoneSelector from '@/views/mold/components/SharedCompatiblePhoneSelector.vue'
import ManufacturingRelationPicker from '@/views/mold/components/ManufacturingRelationPicker.vue'
import type {ManufacturingRelation} from '@/api/modules/manufacturing'
const props=defineProps<{productLineId:number;targetPhoneModelId:number;holeType:string;structureDescription:string}>()
const emit=defineEmits<{close:[];selected:[ManufacturingRelation]}>()
const referencePhoneModelId=ref<number>(),showRelations=ref(false)
watch(()=>props.targetPhoneModelId,()=>{referencePhoneModelId.value=undefined;showRelations.value=false})
function choosePhone(values:number[]){referencePhoneModelId.value=values[0];showRelations.value=!!values[0]&&values[0]!==props.targetPhoneModelId}
</script>
<template><el-dialog :model-value="true" :title="uiText('选择已开模机型及实体模具')" width="min(800px,96vw)" append-to-body :close-on-click-modal="false" @close="emit('close')"><el-alert :title="uiText('新机型映射编码关联到所选实物套；审批通过且工程确认后生效。')" type="info" :closable="false"/><el-form label-position="top"><el-form-item :label="uiText('已开模基准机型')"><SharedCompatiblePhoneSelector :model-value="referencePhoneModelId?[referencePhoneModelId]:[]" :multiple="false" @update:model-value="choosePhone"/><small v-if="referencePhoneModelId===targetPhoneModelId">{{uiText('基准机型必须不同于本次申请机型。')}}</small></el-form-item></el-form><ManufacturingRelationPicker v-if="showRelations&&referencePhoneModelId&&referencePhoneModelId!==targetPhoneModelId" :product-line-id="productLineId" :phone-model-id="referencePhoneModelId" :hole-type="holeType" @close="emit('close')" @selected="emit('selected',$event)"/><p v-else>{{uiText('选择基准机型后，核对其已确认的制造关系和实物套。')}}</p></el-dialog></template>
