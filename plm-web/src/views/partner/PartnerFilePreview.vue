<script setup lang="ts">
import { t as uiText } from '@/locales'

import {computed,ref,watch} from 'vue'
import {fileBlob} from '@/api/modules/partner'
import {useBlobPreview} from '@/composables/useBlobPreview'
import {attachmentPreviewKey} from '@/utils/authorizedBlobQueue'
const props=defineProps<{file:{id:number;name?:string};files?:{id:number;name?:string}[]}>()
const emit=defineEmits<{close:[]}>()
const index=ref(0),items=computed(()=>props.files?.length?props.files:[props.file]),current=computed(()=>items.value[index.value]||props.file)
watch(()=>props.file.id,id=>{index.value=Math.max(0,items.value.findIndex(file=>file.id===id))},{immediate:true})
const {url,type,loading,error,retry,imageFailed}=useBlobPreview(()=>{const id=current.value.id;return {key:attachmentPreviewKey(id),fetch:(signal:AbortSignal)=>fileBlob(id,true,{signal})}},{imageOnly:false,priority:true})
</script>
<template><el-dialog :model-value="true" :title="current.name||uiText('图片与附件预览')" width="min(1000px,96vw)" top="20px" append-to-body class="partner-file-preview" @close="emit('close')"><nav v-if="items.length>1" class="preview-nav" :aria-label='uiText("切换预览资料")'><el-button :disabled="index===0" @click="index--">{{ uiText("上一张") }}</el-button><span>{{index+1}} / {{items.length}}</span><el-button :disabled="index===items.length-1" @click="index++">{{ uiText("下一张") }}</el-button></nav><div v-if="loading" class="preview-state" role="status">{{ uiText("正在加载预览，请稍候…") }}</div><div v-else-if="error" class="preview-state" role="alert"><span>{{error}}</span><el-button type="primary" @click="retry">{{ uiText("重新加载") }}</el-button></div><img v-else-if="url&&type.startsWith('image/')" :src="url" :alt="current.name||'产品图片'" class="preview-image" @error="imageFailed"/><iframe v-else-if="url&&(type.includes('pdf')||type.startsWith('text/'))" :src="url" class="preview-frame"/><el-empty v-else :description='uiText("此格式请下载查看")'/></el-dialog></template>
<style scoped>.preview-nav{display:flex;justify-content:center;align-items:center;gap:12px;margin-bottom:8px;font-size:12px}.preview-image{display:block;max-width:100%;max-height:calc(100dvh - 180px);object-fit:contain;margin:auto}.preview-frame{width:100%;height:65vh;border:0}.preview-state{min-height:180px;display:flex;align-items:center;justify-content:center;gap:12px;flex-wrap:wrap;color:var(--el-text-color-secondary)}:global(.partner-file-preview){max-height:calc(100dvh - 40px);display:flex;flex-direction:column;margin-bottom:20px}:global(.partner-file-preview>.el-dialog__body){min-height:0;overflow:auto}</style>
