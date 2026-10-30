<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,ref,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {getMoldInventoryOptions,type MoldInventoryOption} from '@/api/modules/molds'
import {useUserStore} from '@/stores/user'
const props=defineProps<{modelValue:boolean;part:string}>()
const emit=defineEmits<{'update:modelValue':[boolean];stage:[MoldInventoryOption,string]}>()
const user=useUserStore(),keyword=ref(''),options=ref<MoldInventoryOption[]>([]),selected=ref<number>(),confirmed=ref(false),reason=ref(''),loading=ref(false),error=ref('')
const visible=computed({get:()=>props.modelValue,set:v=>emit('update:modelValue',v)})
let generation=0
watch(()=>props.modelValue,v=>{if(v){keyword.value='';options.value=[];selected.value=undefined;confirmed.value=false;reason.value='';error.value=''}})
watch(()=>user.token,()=>{generation++;options.value=[];visible.value=false})
async function search(){if(keyword.value.trim().length<2){ElMessage.warning(uiText('请输入至少两个字符'));return}const gen=++generation,token=user.token;loading.value=true;try{const rows=await getMoldInventoryOptions(keyword.value.trim());if(gen===generation&&token===user.token)options.value=rows}catch{if(gen===generation)error.value='来源资料加载失败'}finally{if(gen===generation)loading.value=false}}
function stage(){const row=options.value.find(r=>r.inventoryId===selected.value);if(!row||!confirmed.value||!reason.value.trim()){ElMessage.warning(uiText('请选择来源，确认属于模具资料并填写核对依据'));return}emit('stage',row,reason.value.trim())}
</script>
<template><el-dialog v-model="visible" :title="part.toUpperCase()+uiText(' · 人工确认来源资料')" width="min(980px,96vw)" :close-on-click-modal="false"><el-alert type="warning" :closable="false" :title='uiText("tooling 包含工具与零件，只关联人工确认的来源。这里不读取库存数量，不推算或新增模具套。")'/><div class="search"><el-input v-model="keyword" :placeholder='uiText("搜索来源原码 / 名称 / 规格")' @keyup.enter="search"/><el-button :loading="loading" @click="search">{{ uiText("搜索资料") }}</el-button></div><el-alert v-if="error" type="error" :title="error"/><el-table :data="options" max-height="380"><el-table-column width="60" :label='uiText("选择")'><template #default="{row}"><el-radio v-model="selected" :value="row.inventoryId"><span/></el-radio></template></el-table-column><el-table-column prop="inventoryCode" :label='uiText("来源原码")'/><el-table-column prop="inventoryName" :label='uiText("名称")' min-width="220"/><el-table-column prop="specification" :label='uiText("规格")'/><el-table-column prop="inventoryType" :label='uiText("原分类")'/></el-table><el-checkbox v-model="confirmed">{{ uiText("已确认是本部件的模具资料，不是工具或零件") }}</el-checkbox><el-input v-model="reason" type="textarea" :rows="3" :placeholder='uiText("人工核对依据（记录到修改历史）")'/><template #footer><el-button @click="visible=false">{{ uiText("取消") }}</el-button><el-button type="primary" @click="stage">{{ uiText("暂存来源关联") }}</el-button></template></el-dialog></template>
<style scoped>.search{display:flex;gap:8px;margin:12px 0}.el-checkbox{margin-top:10px}</style>
