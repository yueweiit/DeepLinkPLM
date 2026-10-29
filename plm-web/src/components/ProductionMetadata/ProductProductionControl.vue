<script setup lang="ts">
import {computed,ref,watch,onMounted} from 'vue'
import {useProductionMetadataStore} from '@/stores/productionMetadata'
import {useUserStore} from '@/stores/user'
import {getProductProductionStatus} from '@/api/modules/productionMetadata'
import {productionStatusName,productionStatusType} from '@/utils/productionStatus'
import type {ProductionMetadataResult,ProductionStatus} from '@/types/production'
import MetadataDialog from './MetadataDialog.vue'
const props=defineProps<{productId:number;modelValue?:ProductionStatus;label?:string}>()
const emit=defineEmits<{'update:modelValue':[ProductionStatus];changed:[ProductionMetadataResult]}>()
const permissions=useProductionMetadataStore(),user=useUserStore(),editor=ref(false),current=ref<ProductionStatus>('unknown')
const items=computed(()=>[{id:props.productId,label:props.label||`产品 #${props.productId}`,productionStatus:current.value}])
let generation=0
watch(()=>[props.productId,props.modelValue,user.token] as const,async([id,value,token])=>{const epoch=++generation;current.value=value||'unknown';editor.value=false;if(!value&&token&&id>0){try{const state=await getProductProductionStatus(id);if(epoch===generation&&id===props.productId&&token===user.token)current.value=state}catch{}}},{immediate:true})
onMounted(()=>void permissions.ensure())
function saved(rows:ProductionMetadataResult[]){const row=rows[0];if(row){generation++;current.value=row.productionStatus;emit('update:modelValue',row.productionStatus);emit('changed',row)}}
</script>
<template><span class="production-control"><el-tag size="small" :type="productionStatusType(current)">生产：{{productionStatusName(current)}}</el-tag><el-button v-if="permissions.canMaintain&&productId>0" size="small" link aria-label="维护产品生产状态" @click.stop="editor=true">维护</el-button><MetadataDialog v-model="editor" kind="product" :items="items" @saved="saved"/></span></template>
<style scoped>.production-control{display:inline-flex;align-items:center;gap:4px;flex-wrap:wrap;max-width:100%}</style>
