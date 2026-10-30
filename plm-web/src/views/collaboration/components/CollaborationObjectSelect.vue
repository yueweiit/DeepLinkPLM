<script setup lang="ts">
import {t as uiText} from '@/locales'

import { computed } from 'vue'
import PhoneObjectSelect from '@/components/PhoneObjectSelect/index.vue'
import type { PhoneObjectOption, PhoneObjectProvider } from '@/components/PhoneObjectSelect/types'
import { getCollaborationReferencePage, getCollaborationReferencePhones } from '@/api/modules/collaboration'
const props=withDefaults(defineProps<{ modelValue: PhoneObjectOption[]; type:'item'|'product'; productLineId?:number|null; ownerItemId?:number; multiple?:boolean; disabled?:boolean; ariaLabel?:string }>(),{multiple:true})
const emit=defineEmits<{'update:modelValue':[PhoneObjectOption[]]}>()
const scopeKey=computed(()=>JSON.stringify([props.type,props.productLineId,props.ownerItemId]))
const provider:PhoneObjectProvider={
  async page(q){const result=await getCollaborationReferencePage({...q,productLineId:props.productLineId||q.productLineId});return {...result,content:result.content.map(r=>({...r,disabled:!!r.linkedItemId&&r.linkedItemId!==props.ownerItemId,disabledReason:r.linkedItemId&&r.linkedItemId!==props.ownerItemId?'已关联其他模具档案，请在原档案核对后处理':undefined}))}},
  phones:q=>getCollaborationReferencePhones({...q,productLineId:props.productLineId||q.productLineId})
}
</script>
<template><PhoneObjectSelect :model-value="modelValue" :provider="provider" :kinds="[{key:type,type,productType:type==='product'?'model_variant':undefined,label:type==='item'?'模具档案':'型号项目',phoneFirst:true}]" :multiple="multiple" :disabled="disabled" :scope-key="scopeKey" :aria-label="ariaLabel || (type==='item'?uiText('关联模具档案'):uiText('关联型号项目'))" @update:model-value="emit('update:modelValue',$event)"/></template>
