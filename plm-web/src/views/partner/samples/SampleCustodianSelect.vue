<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {t as uiText} from '@/locales'
import {getSampleCustodians} from '@/api/modules/samples'
import {useUserStore} from '@/stores/user'
import type {SampleCustodianOption} from '@/types/samples'

const props=withDefaults(defineProps<{modelValue?:number;name?:string;disabled?:boolean;ariaLabel?:string}>(),{name:'',disabled:false,ariaLabel:'实际保管人'})
const emit=defineEmits<{'update:modelValue':[number];'update:name':[string]}>(),user=useUserStore()
const options=ref<SampleCustodianOption[]>([]),loading=ref(false),error=ref('')
const displayValue=computed(()=>props.modelValue||(props.name?'legacy':undefined))
const retainedOption=computed(()=>props.modelValue&&!options.value.some(option=>option.userId===props.modelValue)?{userId:props.modelValue,name:props.name||(props.modelValue===user.profile?.userId?user.profile.userName:'')}:undefined)
let sequence=0,disposed=false
async function load(){
 const ticket=++sequence,token=user.token,actor=user.profile?.userId;loading.value=true;error.value=''
 try{const result=await getSampleCustodians();if(!disposed&&ticket===sequence&&token===user.token&&actor===user.profile?.userId)options.value=result.content}
 catch{if(!disposed&&ticket===sequence)error.value='保管人列表读取失败，请重试'}
 finally{if(!disposed&&ticket===sequence)loading.value=false}
}
function choose(value:unknown){const option=options.value.find(option=>option.userId===value);if(!option)return;emit('update:modelValue',option.userId);emit('update:name',option.name)}
watch(()=>[user.token,user.profile?.userId],()=>{options.value=[];void load()})
onMounted(load)
onBeforeUnmount(()=>{disposed=true;++sequence})
</script>
<template>
 <div class="sample-custodian">
  <el-select :model-value="displayValue" filterable :disabled="disabled" :loading="loading" :aria-label="uiText(ariaLabel)" :placeholder="uiText('选择系统用户')" @update:model-value="choose">
   <el-option v-if="!modelValue&&name" value="legacy" :label="name" disabled/>
   <el-option v-if="retainedOption?.name" :value="retainedOption.userId" :label="retainedOption.name" disabled/>
   <el-option v-for="option in options" :key="option.userId" :value="option.userId" :label="option.name+(option.departmentName?' · '+option.departmentName:'')"/>
  </el-select>
  <div v-if="error" class="custodian-error" role="alert">{{uiText(error)}} <el-button link size="small" :disabled="disabled||loading" @click="load">{{uiText('重试')}}</el-button></div>
 </div>
</template>
<style scoped>.sample-custodian{width:100%;min-width:0}.sample-custodian .el-select{width:100%}.custodian-error{font-size:12px;color:var(--el-color-danger);line-height:1.5}</style>
