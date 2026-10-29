<script setup lang="ts">
import {computed,inject,ref} from 'vue'
import {formContextKey} from 'element-plus'
import PhoneModelMultiSelect from '@/components/PhoneModelMultiSelect/index.vue'
import {phoneSelectionLabel,unavailablePhone,type PhoneModelMultiSelectProps} from '@/components/PhoneModelMultiSelect/selection'
import type {CompatiblePhone} from '@/types/molds'
import {useUserStore} from '@/stores/user'
import PhoneReferenceManager from './PhoneReferenceManager.vue'

// Existing business callers keep their stable-ID events and reference-maintenance permissions.
const props=withDefaults(defineProps<PhoneModelMultiSelectProps & {allowManage?:boolean;reviewPhoneId?:number}>(),{modelValue:()=>[],members:()=>[],disabled:false,multiple:true,allowManage:false})
const emit=defineEmits<{'update:modelValue':[number[]];selected:[CompatiblePhone[]];referenceChanged:[]}>()
const user=useUserStore(),form=inject(formContextKey,undefined),isDisabled=computed(()=>props.disabled||!!form?.disabled)
const selector=ref<InstanceType<typeof PhoneModelMultiSelect>>(),manager=ref(false),managerTab=ref('groups')
const selectionProps=computed(()=>{const {allowManage:_manage,reviewPhoneId:_review,...rest}=props;return rest})
const reviewPhone=computed(()=>props.members.find(p=>p.phoneModelId===props.reviewPhoneId))
function maintain(tab:string){selector.value?.cancel();managerTab.value=tab;manager.value=true}
function refreshed(){void selector.value?.refresh();emit('referenceChanged')}
function addReviewPhone(){const p=reviewPhone.value;if(!user.token||isDisabled.value||!p||unavailablePhone(p)||props.modelValue.includes(p.phoneModelId))return;const ids=props.multiple?[...props.modelValue,p.phoneModelId].sort((a,b)=>a-b):[p.phoneModelId];emit('update:modelValue',ids);emit('selected',ids.map(id=>props.members.find(p=>p.phoneModelId===id)).filter((p):p is CompatiblePhone=>!!p))}
</script>
<template>
  <div class="compatible-selector">
    <PhoneModelMultiSelect ref="selector" v-bind="selectionProps" @update:model-value="emit('update:modelValue',$event)" @selected="emit('selected',$event)"><template #tools><el-button v-if="allowManage" link :disabled="isDisabled" @click="maintain('groups')">维护品牌 / 手机资料</el-button><el-button v-if="user.hasPermission('collaboration:view')" link :disabled="isDisabled" @click="maintain('issues')">资料缺项／冲突</el-button></template></PhoneModelMultiSelect>
    <PhoneReferenceManager v-model="manager" :editable="allowManage" :initial-tab="managerTab" @changed="refreshed"/>
    <div v-if="reviewPhone" class="review-phone" aria-label="本次待核对手机"><span>{{phoneSelectionLabel(reviewPhone)}}</span><span v-if="modelValue.includes(reviewPhone.phoneModelId)">已在本档，请选择适用套并填写 PC／TPU 依据。</span><el-button v-else size="small" :disabled="isDisabled||!!unavailablePhone(reviewPhone)" @click="addReviewPhone">加入本档核对</el-button><span v-if="unavailablePhone(reviewPhone)">{{unavailablePhone(reviewPhone)}}，请先在现有手机资料中处理。</span></div>
  </div>
</template>
<style scoped>
.compatible-selector{min-width:0;width:100%;max-width:100%}.review-phone{display:flex;align-items:center;flex-wrap:wrap;gap:6px;font-size:12px;line-height:18px;color:#526c88;margin-top:6px}
</style>
