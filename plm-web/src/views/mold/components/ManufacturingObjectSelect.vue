<script setup lang="ts">
import {computed,ref} from 'vue'
import PhoneObjectSelect from '@/components/PhoneObjectSelect/index.vue'
import type {PhoneObjectOption,PhoneObjectProvider} from '@/components/PhoneObjectSelect/types'
import {getManufacturingOptions,type ManufacturingObject} from '@/api/modules/manufacturing'
const props=defineProps<{modelValue:number[];kind:'products'|'skus';productLineId:number;manufacturingProductId?:number;known?:ManufacturingObject[];multiple?:boolean;disabled?:boolean;label:string}>()
const emit=defineEmits<{'update:modelValue':[number[]]}>()
const cache=ref<Record<number,PhoneObjectOption>>({})
const option=(row:ManufacturingObject):PhoneObjectOption=>({type:props.kind,id:row.id,code:row.code,name:[row.code,row.name].filter(Boolean).join(' · '),subtitle:[row.phoneModelCode,row.colorCode].filter(Boolean).join(' · '),productLineId:props.productLineId})
const selected=computed(()=>props.modelValue.map(id=>props.known?.find(row=>row.id===id)).map((row,index)=>row?option(row):cache.value[props.modelValue[index]!]||{type:props.kind,id:props.modelValue[index]!,code:`#${props.modelValue[index]}`,name:props.label}))
const provider:PhoneObjectProvider={async page(query){const page=await getManufacturingOptions({kind:props.kind,productLineId:props.productLineId,manufacturingProductId:props.manufacturingProductId,keyword:query.keyword,page:query.page,size:query.size});const content=page.content.map(option);content.forEach(row=>cache.value[row.id]=row);return {...page,content,brands:[],productLines:[]}},async phones(){return {content:[],totalElements:0,brands:[],productLines:[],unlinkedCount:0}}}
</script>
<template><PhoneObjectSelect :model-value="selected" :provider="provider" :kinds="[{key:kind,type:kind,label,phoneFirst:false}]" :multiple="multiple" :disabled="disabled" :scope-key="`${productLineId}:${manufacturingProductId||0}:${kind}`" :aria-label="label" :placeholder="label" @update:model-value="emit('update:modelValue',$event.map(row=>row.id))"/></template>
