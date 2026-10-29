<script setup lang="ts">
import {computed} from 'vue'
import MasterDataLifecycleDialog from '@/components/MasterDataLifecycleDialog.vue'
import {getSupplierReferences,supplierLifecycle,type SupplierLifecycleAction} from '@/api/modules/supplier'
import type {MasterDataInspection,MasterDataLifecycleInput} from '@/types/masterDataLifecycle'
const props=defineProps<{supplier?:{supplierCode:string;supplierName:string};action:SupplierLifecycleAction;editable:boolean;context:string}>()
const emit=defineEmits<{close:[];completed:[result:{supplierCode:string;deleted:boolean;status:string;version:number}];open:[path:string]}>()
const record=computed(()=>props.supplier?{id:props.supplier.supplierCode,code:props.supplier.supplierCode,name:props.supplier.supplierName}:undefined)
async function inspect(id:number|string):Promise<MasterDataInspection>{
 const value=await getSupplierReferences(String(id))
 return {...value,id:value.supplierCode,code:value.supplierCode,name:value.supplierName,
  blockedReason:value.status==='inactive'&&!value.canActivate?'启用前请在编辑档案中确认供应商类型与身份':undefined}
}
function perform(id:number|string,action:SupplierLifecycleAction,input:MasterDataLifecycleInput){return supplierLifecycle(String(id),action,input)}
function completed(result:unknown){emit('completed',result as {supplierCode:string;deleted:boolean;status:string;version:number})}
</script>
<template>
 <MasterDataLifecycleDialog :record="record" kind-label="供应商" :action="action" :editable="editable" :context="context" :load="inspect" :perform="perform" @close="emit('close')" @completed="completed" @open="emit('open',$event)"/>
</template>
